package com.replaymod.recording.packet;

import com.github.steveice10.netty.buffer.PooledByteBufAllocator;
import com.github.steveice10.packetlib.tcp.io.ByteBufNetOutput;
import com.google.gson.Gson;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ReplayStorageLayout;
import com.replaymod.core.files.ManagedReplayFile;
import com.replaymod.core.files.ReplayFileOperations;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.core.utils.Utils;
import com.replaymod.core.versions.MCVer;
import com.replaymod.editor.gui.MarkerProcessor;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.Setting;
import com.replaymod.recording.gui.GuiSavingReplay;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.io.ReplayOutputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import de.johni0702.minecraft.gui.container.VanillaGuiScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.crash.CrashReport;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//#if MC>=12006
import com.replaymod.recording.mixin.DecoderHandlerAccessor;
import net.minecraft.network.NetworkState;
import net.minecraft.network.handler.DecoderHandler;
import net.minecraft.network.handler.NetworkStateTransitions;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.state.LoginStates;
//#endif

//#if MC>=12002
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
//#else
//$$ import net.minecraft.network.packet.s2c.play.PlayerSpawnS2CPacket;
//#endif

//#if MC>=10800
//#if MC<10904
//$$ import net.minecraft.network.play.server.S46PacketSetCompressionLevel;
//#endif
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.NetworkSide;
//#endif

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.replaymod.core.versions.MCVer.*;
import static com.replaymod.replaystudio.util.Utils.writeInt;
import static java.util.Objects.requireNonNull;

//#if MC>=11904
import java.util.ArrayList;
//#endif

@ChannelHandler.Sharable // so we can re-order it
public class PacketListener extends ChannelInboundHandlerAdapter {

    public static final String RAW_RECORDER_KEY = "replay_recorder_raw";
    public static final String DECODED_RECORDER_KEY = "replay_recorder_decoded";

    public static final String DECOMPRESS_KEY = "decompress";
    public static final String DECODER_KEY = "decoder";

    private static final MinecraftClient mc = getMinecraft();
    private static final Logger logger = LogManager.getLogger();

    private final ReplayMod core;
    private final Path outputPath;
    private final ReplayStorageLayout layout;
    private final Object admission = new Object();
    private boolean accepting = true, finishing, finalQueued;
    private int pendingMain;
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private final ReplayFile replayFile;

    private final ResourcePackRecorder resourcePackRecorder;

    private final ExecutorService saveService = Executors.newSingleThreadExecutor();
    private final ReplayOutputStream packetOutputStream;

    private ReplayMetaData metaData;

    private final Channel channel;
    private Packet currentRawPacket;

    private final long startTime;
    private volatile long lastSentPacket;
    private long timePassedWhilePaused;
    private volatile boolean serverWasPaused;

    /**
     * Used to keep track of the last metadata save job submitted to the save service and
     * as such prevents unnecessary writes.
     */
    private final AtomicInteger lastSaveMetaDataId = new AtomicInteger();

    public PacketListener(ReplayMod core, Channel channel, Path outputPath, ReplayFile replayFile, ReplayMetaData metaData) throws IOException {
        this(core, channel, outputPath, replayFile, metaData, core.folders.snapshotStorageLayout());
    }

    public PacketListener(ReplayMod core, Channel channel, Path outputPath, ReplayFile replayFile,
            ReplayMetaData metaData, ReplayStorageLayout layout) throws IOException {
        this.core = core;
        this.layout = layout;
        this.channel = channel;
        this.outputPath = outputPath;
        this.replayFile = replayFile;
        this.metaData = metaData;
        this.resourcePackRecorder = new ResourcePackRecorder(replayFile);
        this.packetOutputStream = replayFile.writePacketData();
        this.startTime = metaData.getDate();

        saveMetaData();
    }

    private void failed(Throwable failure) {
        firstFailure.compareAndSet(null, failure);
        logger.error("Recording task failed; output=" + outputPath, failure);
    }

    /** A ticket is acquired BEFORE main-thread dispatch. Sealing cannot revoke an already admitted ticket. */
    private boolean admit(Runnable rejected) {
        synchronized (admission) {
            if (!accepting) { rejected.run(); return false; }
            pendingMain++;
            return true;
        }
    }

    private void onMain(Runnable work, Runnable rejected) {
        if (admit(rejected)) dispatchMain(work, rejected);
    }

    private void dispatchMain(Runnable work, Runnable rejected) {
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable task = () -> {
            if (!claimed.compareAndSet(false, true)) return;
            try { work.run(); }
            catch (Throwable failure) { failed(failure); }
            finally { mainDone(); }
        };
        try {
            // MC clears its own task queue on disconnect; admitted recording work must survive it.
            if (mc.isOnThread()) task.run(); else core.runLater(task);
        } catch (Throwable failure) {
            if (claimed.compareAndSet(false, true)) {
                try { rejected.run(); } finally { failed(failure); mainDone(); }
            } else failed(failure);
        }
    }

    private void mainDone() {
        boolean finish;
        synchronized (admission) { pendingMain--; finish = finishing && pendingMain == 0; }
        if (finish) queueFinalMetadata();
    }

    private boolean submit(Runnable task) {
        try {
            saveService.submit(() -> {
                try { task.run(); } catch (Throwable failure) { failed(failure); }
            });
            return true;
        } catch (Throwable failure) { failed(failure); return false; }
    }

    private ReplayMetaData snapshotMetadata() {
        // All mutable metadata is read on the client thread, never by the writer.
        Gson gson = new Gson();
        return gson.fromJson(gson.toJson(metaData), ReplayMetaData.class);
    }

    private void writeMetadata(ReplayMetaData snapshot) {
        try {
            synchronized (replayFile) {
                if (ReplayMod.isMinimalMode()) {
                    snapshot.setFileFormat("MCPR");
                    snapshot.setFileFormatVersion(ReplayMetaData.CURRENT_FILE_FORMAT_VERSION);
                    snapshot.setProtocolVersion(MCVer.getProtocolVersion());
                    snapshot.setGenerator("ReplayMod in Minimal Mode");
                    try (OutputStream out = replayFile.write("metaData.json")) {
                        out.write(new Gson().toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else replayFile.writeMetaData(MCVer.getPacketTypeRegistry(State.LOGIN), snapshot);
            }
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private void saveMetaData() {
        onMain(() -> {
            int id = lastSaveMetaDataId.incrementAndGet();
            ReplayMetaData snapshot = snapshotMetadata();
            submit(() -> { if (lastSaveMetaDataId.get() == id) writeMetadata(snapshot); });
        }, () -> {});
    }

    private void queueFinalMetadata() {
        synchronized (admission) {
            if (finalQueued || !finishing || pendingMain != 0) return;
            finalQueued = true;
        }
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable finish = () -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                metaData.setDuration((int) lastSentPacket);
                lastSaveMetaDataId.incrementAndGet();
                ReplayMetaData snapshot = snapshotMetadata();
                // The only internal final write bypasses public admission. No accepted ticket remains.
                submit(() -> writeMetadata(snapshot));
            } catch (Throwable failure) { failed(failure); }
            finally { saveService.shutdown(); synchronized (admission) { admission.notifyAll(); } }
        };
        try { if (mc.isOnThread()) finish.run(); else core.runLater(finish); }
        catch (Throwable failure) {
            failed(failure);
            if (claimed.compareAndSet(false, true)) saveService.shutdown();
            synchronized (admission) { admission.notifyAll(); }
        }
    }

    public void save(net.minecraft.network.packet.Packet packet) {
        if (!admit(() -> {})) return;
        final Packet encoded;
        try {
            // Preserve the inbound pipeline state while encoding, but carry this SAME ticket
            // through main-thread dispatch. Never recurse through public save after sealing.
            encoded = encodeMcPacket(getConnectionState(), packet);
        } catch (Throwable failure) { failed(failure); mainDone(); return; }
        dispatchMain(() -> saveAccepted(encoded), encoded::release);
    }

    /** Transfers ownership of one packet reference, including on rejection. */
    public void save(Packet packet) {
        onMain(() -> saveAccepted(packet), packet::release);
    }

    private void saveAccepted(Packet packet) {
        boolean transferred = false;
        try {
            long now = System.currentTimeMillis();
            if (serverWasPaused) {
                timePassedWhilePaused = now - startTime - lastSentPacket;
                serverWasPaused = false;
            }
            int timestamp = (int) (now - startTime - timePassedWhilePaused);
            lastSentPacket = timestamp;
            PacketData packetData = new PacketData(timestamp, packet);
            transferred = submit(() -> writePacket(packetData));
        } finally { if (!transferred) packet.release(); }
    }

    private void writePacket(PacketData data) {
        var buf = data.getPacket().getBuf();
        if (ReplayMod.isMinimalMode()) {
            com.github.steveice10.netty.buffer.ByteBuf ids = null;
            try {
                ids = PooledByteBufAllocator.DEFAULT.buffer();
                new ByteBufNetOutput(ids).writeVarInt(data.getPacket().getId());
                int idLength = ids.readableBytes(), length = buf.readableBytes();
                writeInt(packetOutputStream, (int) data.getTime());
                writeInt(packetOutputStream, idLength + length);
                ids.readBytes(packetOutputStream, idLength);
                buf.getBytes(buf.readerIndex(), packetOutputStream, length);
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            finally { if (ids != null) ids.release(); data.release(); }
        } else {
            // Verified ReplayStudio doWrite consumes the packet on success AND IO failure.
            // A failure in its synthetic-login preamble precedes doWrite, however. The transferred
            // packet may not be used concurrently by its caller; compare the public reference count
            // so only that pre-consumption failure releases here (never blindly double-release).
            int references = buf.refCnt();
            try { packetOutputStream.write(data); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            finally { if (buf.refCnt() == references) data.release(); }
        }
    }

    public void abortRecording(Throwable failure) {
        failed(failure);
        channelInactive(null);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        synchronized (admission) {
            if (finishing) return;
            accepting = false;
            finishing = true;
        }
        try {
            core.runLater(() -> {
                ConnectionEventHandler handler = ReplayModRecording.instance.getConnectionEventHandler();
                if (handler.getPacketListener() == this) handler.reset();
            });
        } catch (Throwable failure) { failed(failure); }
        GuiSavingReplay gui = new GuiSavingReplay(core, layout);
        new Thread(() -> finishRecording(gui), "replaymod-recording-save").start();
        queueFinalMetadata();
    }

    private void preserve(Throwable failure) {
        if (replayFile instanceof ManagedReplayFile managed) {
            managed.preserveRecovery(failure);
            try { logger.error("Recovery record: " + managed.recoveryRecord()); }
            catch (IOException diagnostic) { failure.addSuppressed(diagnostic); }
        }
    }

    private void showFailure(GuiSavingReplay gui, Throwable failure) {
        preserve(failure);
        try {
            core.runLater(() -> Utils.error(logger, VanillaGuiScreen.wrap(mc.currentScreen),
                    CrashReport.create(failure, "Saving replay file; output=" + outputPath), gui::close));
        } catch (Throwable scheduling) { logger.error("Cannot display recording failure", scheduling); }
    }

    private void finishRecording(GuiSavingReplay gui) {
        boolean interrupted = false, reported = false;
        try {
            try { core.runLater(gui::open); } catch (Throwable scheduling) { failed(scheduling); }
            // GUI scheduling failure does not permit closing underneath the still-live writer.
            // Only the worker waits. A timeout/interruption is a failure, not a fake flush receipt.
            while (!saveService.isTerminated()) {
                try {
                    if (!saveService.awaitTermination(10, TimeUnit.SECONDS)) {
                        if (!reported) {
                            failed(new IOException("Recording writer/main admission did not drain within 10 seconds; retaining recovery"));
                            showFailure(gui, firstFailure.get()); reported = true;
                        }
                    }
                } catch (InterruptedException failure) {
                    interrupted = true; failed(failure);
                    if (!reported) { showFailure(gui, firstFailure.get()); reported = true; }
                    // Continue quarantining the live writer; NEVER shutdownNow or close under it.
                }
            }
            // No replayFile monitor is held while taking the recorder monitor (lock order RP -> file).
            try { resourcePackRecorder.seal(); } catch (Throwable failure) { failed(failure); }
            if (firstFailure.get() != null) preserve(firstFailure.get());
            try { packetOutputStream.close(); } catch (Throwable failure) { failed(failure); }
            if (firstFailure.get() != null) {
                preserve(firstFailure.get());
                try { replayFile.close(); } catch (Throwable close) { firstFailure.get().addSuppressed(close); }
                if (!reported) showFailure(gui, firstFailure.get());
                return;
            }
            boolean producesOutput = MarkerProcessor.producesAnyOutput(replayFile);
            replayFile.save();
            replayFile.close();
            if (!(replayFile instanceof ManagedReplayFile managed) || !managed.isReleased()) {
                throw new IOException("Recording close has no actual Managed release receipt: " + outputPath);
            }
            if (!producesOutput) {
                if (layout.rawBackupEnabled()) {
                    Files.createDirectories(layout.rawRoot());
                    ReplayFileOperations.moveToRaw(outputPath, layout.rawRoot());
                } else Files.delete(outputPath); // normal no-output only after successful save and true release
                core.runLater(gui::close);
                return;
            }
            List<Pair<Path, ReplayMetaData>> outputs;
            if (core.getSettingsRegistry().get(Setting.AUTO_POST_PROCESS) && !ReplayMod.isMinimalMode()) {
                outputs = MarkerProcessor.apply(outputPath,
                        value -> core.runLater(() -> gui.getProgressBar().setProgress(value)), layout);
            } else outputs = Collections.singletonList(Pair.of(outputPath, metaData));
            core.runLater(() -> gui.presentRenameDialog(outputs));
        } catch (Throwable failure) {
            failed(failure); preserve(failure);
            // Initialization/save failure may leave dirty entries. Preserve BEFORE requesting close.
            try { replayFile.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            if (!reported) showFailure(gui, failure);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NetworkPhase connectionState = getConnectionState();

        Packet packet = null;
        if (msg instanceof ByteBuf) {
            // for regular connections, we're expecting to observe `ByteBuf`s here
            ByteBuf buf = (ByteBuf) msg;
            if (buf.readableBytes() > 0) {
                packet = decodePacket(connectionState, buf);
            }
        } else if (msg instanceof net.minecraft.network.packet.Packet) {
            // for integrated server connections MC is passing the packet objects directly, so we need to encode them
            // ourselves to be able to store them
            //#if MC>=12006
            // No longer applies. MC now encodes packets even for the integrated server connection.
            //#else
            //#if MC>=11904
            //#if MC>=12002
            //$$ PacketBundleHandler bundleHandler = ctx.channel().attr(ClientConnection.CLIENTBOUND_PROTOCOL_KEY).get().getBundler();
            //#else
            //$$ PacketBundleHandler bundleHandler = ctx.channel().attr(PacketBundleHandler.KEY).get().getBundler(NetworkSide.CLIENTBOUND);
            //#endif
            //$$ List<Packet> packets = new ArrayList<>(1);
            //$$ bundleHandler.forEachPacket((net.minecraft.network.packet.Packet<?>) msg, unbundledPacket -> {
            //$$     try {
            //$$         packets.add(encodeMcPacket(connectionState, unbundledPacket));
            //$$     } catch (Exception e) {
            //$$         throw new RuntimeException(e);
            //$$     }
            //$$ });
            //$$ if (packets.size() > 1) {
            //$$     packets.forEach(this::save);
            //$$     super.channelRead(ctx, msg);
            //$$     return;
            //$$ }
            //$$ packet = packets.isEmpty() ? null : packets.get(0);
            //#else
            //$$ packet = encodeMcPacket(connectionState, (net.minecraft.network.Packet) msg);
            //#endif
            //#endif
        }

        currentRawPacket = packet;
        try {
            super.channelRead(ctx, msg);
        } finally {
            if (currentRawPacket != null) {
                currentRawPacket.release();
                currentRawPacket = null;
            }
        }
    }

    private NetworkPhase getConnectionState() {
        //#if MC>=12006
        var decoderHandler = (DecoderHandlerAccessor<?>) channel.pipeline().get(DecoderHandler.class);
        if (decoderHandler == null) {
            return NetworkPhase.LOGIN;
        }
        return decoderHandler.getState().id();
        //#elseif MC>=12002
        //$$ var decoderHandler = (DecoderHandlerAccessor<?>) channel.pipeline().get(DecoderHandler.class);
        //$$ if (decoderHandler == null) {
        //$$     return NetworkPhase.LOGIN;
        //$$ }
        //$$ return decoderHandler.getState().id();
        //#elseif MC>=12002
        //$$ AttributeKey<NetworkState.PacketHandler<?>> key = ClientConnection.CLIENTBOUND_PROTOCOL_KEY;
        //$$ return ctx.channel().attr(key).get().getState();
        //#else
        //$$ AttributeKey<NetworkState> key = ClientConnection.PROTOCOL_ATTRIBUTE_KEY;
        //$$ return ctx.channel().attr(key).get();
        //#endif
    }

    private Packet encodeMcPacket(NetworkPhase connectionState, net.minecraft.network.packet.Packet packet) throws Exception {
        //#if MC>=12006
        var byteBuf = Unpooled.buffer();
        try {
            NetworkState<?> state;
            if (connectionState == NetworkPhase.LOGIN) {
                // Special case for our initial LoginSuccess packet which we only save after the pipeline has already
                // started to transition to the next phase, so we can't just use its DecoderHandler (and luckily we
                // also don't need it).
                state = LoginStates.S2C;
            } else {
                state = ((DecoderHandlerAccessor<?>) channel.pipeline().get(DecoderHandler.class)).getState();
            }
            state.codec().encode(byteBuf, packet);
            return decodePacket(state.id(), byteBuf);
        } finally {
            byteBuf.release();
        }
        //#else
        //#if MC>=10800
        //#if MC>=12002
        // Special case for our initial LoginSuccess packet which we only save after the pipeline has already
        // started to transition to the next phase
        //$$ if (packet instanceof LoginSuccessS2CPacket) {
        //$$     connectionState = NetworkState.LOGIN;
        //$$ }
        //#endif
        //$$ Integer packetId = connectionState.getPacketId(NetworkSide.CLIENTBOUND, packet);
        //#else
        //$$ Integer packetId = (Integer) connectionState.func_150755_b().inverse().get(packet.getClass());
        //#endif
        //$$ if (packetId == null) {
        //$$     throw new IOException("Unknown packet type:" + packet.getClass());
        //$$ }
        //$$ ByteBuf byteBuf = Unpooled.buffer();
        //$$ try {
        //$$     packet.write(new PacketByteBuf(byteBuf));
        //$$     return new Packet(
        //$$             MCVer.getPacketTypeRegistry(connectionState),
        //$$             packetId,
        //$$             com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(
        //$$                     byteBuf.array(),
        //$$                     byteBuf.arrayOffset(),
        //$$                     byteBuf.readableBytes()
        //$$             )
        //$$     );
        //$$ } finally {
        //$$     byteBuf.release();
        //$$ }
        //#endif
    }

    private static Packet decodePacket(NetworkPhase connectionState, ByteBuf buf) {
        PacketByteBuf packetBuf = new PacketByteBuf(buf.slice());
        int packetId = packetBuf.readVarInt();
        byte[] bytes = new byte[packetBuf.readableBytes()];
        packetBuf.readBytes(bytes);
        return new Packet(
                MCVer.getPacketTypeRegistry(connectionState),
                packetId,
                com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(bytes)
        );
    }

    public void addMarker(String name) {
        addMarker(name, (int) getCurrentDuration());
    }

    public void addMarker(String name, int timestamp) {
        onMain(() -> {
            Entity view = mc.getCameraEntity();
            Marker marker = new Marker();
            marker.setName(name);
            marker.setTime(timestamp);
            if (view != null) {
                marker.setX(view.getX()); marker.setY(view.getY()); marker.setZ(view.getZ());
                marker.setYaw(view.getYaw()); marker.setPitch(view.getPitch());
            }
            submit(() -> {
                synchronized (replayFile) {
                    try {
                        Set<Marker> markers = replayFile.getMarkers().or(HashSet::new);
                        markers.add(marker);
                        replayFile.writeMarkers(markers);
                    } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
            });
        }, () -> {});
    }

    public long getCurrentDuration() {
        return lastSentPacket;
    }

    public void setServerWasPaused() {
        this.serverWasPaused = true;
    }

    public ResourcePackRecorder getResourcePackRecorder() {
        return resourcePackRecorder;
    }

    public class DecodedPacketListener extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

            if (msg instanceof LoginCompressionS2CPacket) {
                super.channelRead(ctx, msg);
                return;
            }
            //#if MC<10904
            //$$ if (msg instanceof S46PacketSetCompressionLevel) {
            //$$     super.channelRead(ctx, msg);
            //$$     return;
            //$$ }
            //#endif

            if (msg instanceof CustomPayloadS2CPacket) {
                CustomPayloadS2CPacket packet = (CustomPayloadS2CPacket) msg;
                if (Restrictions.PLUGIN_CHANNEL.equals(packet.payload().getId().id())) {
                    save(new DisconnectS2CPacket(net.minecraft.text.Text.literal("Please update to view this replay.")));
                }
            }

            //#if MC>=12002
            if (msg instanceof EntitySpawnS2CPacket packet && packet.getEntityType() == EntityType.PLAYER) {
                UUID uuid = packet.getUuid();
            //#else
            //$$ if (msg instanceof PlayerSpawnS2CPacket) {
            //$$     //#if MC>=10800
            //$$     UUID uuid = ((PlayerSpawnS2CPacket) msg).getPlayerUuid();
            //$$     //#else
            //$$     //$$ UUID uuid = ((S0CPacketSpawnPlayer) msg).func_148948_e().getId();
            //#endif
            //#endif
                onMain(() -> {
                    Set<String> uuids = new HashSet<>(Arrays.asList(metaData.getPlayers()));
                    uuids.add(uuid.toString());
                    metaData.setPlayers(uuids.toArray(new String[0]));
                    ReplayMetaData snapshot = snapshotMetadata();
                    submit(() -> writeMetadata(snapshot));
                }, () -> {});
            }

            if (msg instanceof ResourcePackSendS2CPacket) {
                ClientConnection connection = ctx.pipeline().get(ClientConnection.class);
                save(resourcePackRecorder.handleResourcePack(connection, (ResourcePackSendS2CPacket) msg));
                //#if MC>=12003
                super.channelRead(ctx, msg);
                //#endif
                //$$ return;
            }

            //#if MC>=12006
            // Special case: We need to inject another packet before this one, and we can only construct that
            // packet on the main thread, so we'll skip saving this packet here and then manually re-add it after
            // that other packet has been injected.
            // See MixinNetHandlerConfigClient.
            if (msg instanceof ReadyS2CPacket) {
                super.channelRead(ctx, msg);
                return;
            }
            //#endif

            if (currentRawPacket != null) {
                Packet owned = currentRawPacket;
                currentRawPacket = null;
                save(owned);
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            //#if MC>=12006
            if (msg instanceof NetworkStateTransitions.DecoderTransitioner) {
                // We need our DecodedPacketListener to stay right behind the decoder, however MC will on network state
                // transitions insert the bundler in the middle, so we need to re-position our handler in that case.
                msg = ((NetworkStateTransitions.DecoderTransitioner) msg).andThen(context -> {
                    context.pipeline().remove(this);
                    context.pipeline().addAfter(DECODER_KEY, DECODED_RECORDER_KEY, new DecodedPacketListener());
                });
            }
            //#endif

            super.write(ctx, msg, promise);
        }
    }
}
