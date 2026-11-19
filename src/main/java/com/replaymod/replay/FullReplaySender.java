package com.replaymod.replay;

import com.github.steveice10.packetlib.io.NetOutput;
import com.github.steveice10.packetlib.tcp.io.ByteBufNetOutput;
import com.google.common.base.Preconditions;
import com.replaymod.core.files.ManagedReplayFile;
import com.replaymod.core.files.ReplayFoldersService.ResourcePackLease;
import com.replaymod.core.files.ReplayResourcePackReleaseBridge;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.mixin.MinecraftAccessor;
import com.replaymod.core.mixin.TimerAccessor;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.replay.camera.CameraEntity;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.replay.ReplayFile;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import de.johni0702.minecraft.gui.versions.callbacks.PreTickCallback;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import org.apache.commons.io.IOUtils;

//#if MC>=12002
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
//#else
//#endif

//#if MC>=11904
//#endif

//#if MC>=11903
//#endif

//#if MC==11901 || MC==11902
//$$ import net.minecraft.network.packet.s2c.play.MessageHeaderS2CPacket;
//#endif

//#if MC>=11900
//#else
//$$ import net.minecraft.network.packet.s2c.play.MobSpawnS2CPacket;
//$$ import net.minecraft.network.packet.s2c.play.PaintingSpawnS2CPacket;
//#endif

//#if MC>=11600
//#else
//$$ import net.minecraft.network.packet.s2c.play.EntitySpawnGlobalS2CPacket;
//#endif

//#if MC>=11400
import com.replaymod.core.versions.MCVer;
import net.minecraft.entity.EntityType;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
//#else
//$$ import net.minecraft.client.resources.I18n;
//$$ import net.minecraft.world.EnumDifficulty;
//$$ import net.minecraft.world.World;
//$$ import net.minecraft.world.WorldType;
//$$ import net.minecraft.world.chunk.Chunk;
//$$ import net.minecraft.world.chunk.IChunkProvider;
//$$ import java.util.Iterator;
//#endif

//#if MC>=11400
import net.minecraft.util.Identifier;
//#if MC<11400
//$$ import net.minecraft.world.dimension.DimensionType;
//#endif
//#endif

//#if MC>=11200
//#endif
//#if MC>=11002
import net.minecraft.world.GameMode;
//#else
//$$ import net.minecraft.world.WorldSettings.GameType;
//#endif

//#if MC>=10904
//#else
//$$ import net.minecraft.network.play.server.S21PacketChunkData;
//#endif

//#if MC>=10800
import net.minecraft.network.NetworkSide;
//#else
//$$ import org.apache.commons.io.Charsets;
//#endif

import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.replaymod.core.utils.Utils.DEFAULT_MS_PER_TICK;
import static com.replaymod.core.versions.MCVer.*;
import static com.replaymod.replaystudio.util.Utils.readInt;

/**
 * Sends replay packets to netty channels.
 * Even though {@link Sharable}, this should never be added to multiple pipes at once, it may however be re-added when
 * the replay restart from the beginning.
 */
@Sharable
public class FullReplaySender extends ChannelInboundHandlerAdapter implements ReplaySender {
    /**
     * These packets are ignored completely during replay.
     */
    private static final List<Class> BAD_PACKETS = Arrays.<Class>asList(
            //#if MC>=11404
            PlayerActionResponseS2CPacket.class,
            //#endif
            //#if MC>=11400
            OpenWrittenBookS2CPacket.class,
            OpenScreenS2CPacket.class,
            //#endif
            //#if MC>=11200
            SynchronizeRecipesS2CPacket.class,
            AdvancementUpdateS2CPacket.class,
            SelectAdvancementTabS2CPacket.class,
            //#endif
            //#if MC>=10800
            SetCameraEntityS2CPacket.class,
            TitleS2CPacket.class,
            //#endif
            HealthUpdateS2CPacket.class,
            OpenHorseScreenS2CPacket.class,
            CloseScreenS2CPacket.class,
            ScreenHandlerSlotUpdateS2CPacket.class,
            ScreenHandlerPropertyUpdateS2CPacket.class,
            SignEditorOpenS2CPacket.class,
            StatisticsS2CPacket.class,
            ExperienceBarUpdateS2CPacket.class,
            PlayerAbilitiesS2CPacket.class
    );

    private static int TP_DISTANCE_LIMIT = 128;

    /**
     * The replay handler responsible for the current replay.
     */
    private final ReplayHandler replayHandler;

    /**
     * Whether to work in async mode.
     *
     * When in async mode, a separate thread send packets and waits according to their delays.
     * This is default in normal playback mode.
     *
     * When in sync mode, no packets will be sent until {@link #sendPacketsTill(int)} is called.
     * This is used during path playback and video rendering.
     */
    protected volatile boolean asyncMode;

    /**
     * Timestamp of the last packet sent in milliseconds since the start.
     */
    protected volatile int lastTimeStamp;

    /**
     * @see #currentTimeStamp()
     */
    protected int currentTimeStamp;

    /**
     * The replay file.
     */
    protected ReplayFile replayFile;

    /**
     * The channel used to send packets to minecraft.
     */
    protected Channel channel;

    /**
     * The replay input stream from which new packets are read.
     * When accessing this stream make sure to synchronize on {@code this} as it's used from multiple threads.
     */
    protected ReplayInputStream replayIn;
    // Guarded by this: a failed retirement must never be read again or overwritten.
    private boolean replayInputClosePending;

    // Short request/diagnostic lock only. Never acquire this sender's monitor while holding it.
    private final Object packetCloseStateLock = new Object();
    private Thread packetCloser;
    private long packetCloseRequestSequence;
    private boolean packetInputCloseRequested;
    private boolean packetInputCloseCompleted;
    private boolean replayFileCloseRequested;
    private boolean replayRecoveryIntentRegistered;
    // For ManagedReplayFile this is a real release receipt; otherwise only close() returned.
    private boolean replayFileCloseCompleted;
    private Throwable closePrimary;
    private Throwable firstPacketCloseFailure;
    private Throwable firstFileCloseFailure;

    /**
     * The next packet that should be sent.
     * This is required as some actions such as jumping to a specified timestamp have to peek at the next packet.
     */
    protected PacketData nextPacket;

    /**
     * Which protocol (state) we're currently in.
     */
    private PacketTypeRegistry registry = getPacketTypeRegistry(State.LOGIN);

    /**
     * Whether we need to restart the current replay. E.g. when jumping backwards in time
     */
    protected volatile boolean startFromBeginning = true;
    private volatile boolean restartPending;

    /**
     * Whether to terminate the replay. This only has an effect on the async mode and is {@code true} during sync mode.
     */
    protected volatile boolean terminate;

    /**
     * The speed of the replay. 1 is normal, 2 is twice as fast, 0.5 is half speed and 0 is frozen
     */
    protected double replaySpeed = 1f;

    /**
     * Whether the world has been loaded and the dirt-screen should go away.
     */
    protected boolean hasWorldLoaded;

    /**
     * Whether we are currently in the middle of a bundle packet.
     */
    protected boolean inBundle;

    /**
     * The minecraft instance.
     */
    protected MinecraftClient mc = getMinecraft();

    /**
     * The total length of this replay in milliseconds.
     */
    protected final int replayLength;

    /**
     * Our actual entity id that the server gave to us.
     */
    protected int actualID = -1;

    /**
     * Whether to allow (process) the next player movement packet.
     *
     * Must only be accessed from the main thread.
     */
    protected boolean allowMovement;

    /** One lazy task and one settings snapshot for this sender, including every seek. */
    private final ResourcePackLease resourcePackLease;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean terminationCleaned = new AtomicBoolean();
    private final Object modeLock = new Object();
    private volatile long asyncEpoch;
    private volatile Thread asyncWorker;
    // A forward rush supersedes UI/control intentions, not the current stream's legal packets.
    private final AtomicLong controlEpoch = new AtomicLong();
    // Reader/EOF provenance, or the generation of the established LOGIN boundary before first open.
    private long replayInGeneration;
    // NONE permits a fresh reader only if there is no outstanding restart and the boundary is current.
    private enum EofState { NONE, NEEDS_PAUSE, PAUSED, RESUME }
    // Written under modeLock. Closing the reader does not clear its EOF history.
    private volatile EofState eofState = EofState.NONE;
    // Carry the generation captured when bytes were read through synchronous Netty decoding.
    // A seek while an async packet is sleeping must not give that old resource request a new epoch.
    private final ThreadLocal<Long> dispatchGeneration = new ThreadLocal<>();

    private final EventHandler events = new EventHandler();

    /**
     * Create a new replay sender.
     * @param file The replay file
     */
    public FullReplaySender(ReplayHandler replayHandler, ReplayFile file) throws IOException {
        this.replayHandler = replayHandler;
        this.replayFile = file;
        this.replayLength = file.getMetaData().getDuration();
        this.resourcePackLease = ReplayMod.instance.folders.createResourcePackLease();
        this.replayInGeneration = resourcePackLease.generation();
        try {
            ReplayResourcePackReleaseBridge.attach(resourcePackLease, mc);
            events.register();
        } catch (RuntimeException | Error failure) {
            try { terminateReplay(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void setChannel(Channel channel) {
        if (this.channel != null && this.channel != channel) advancePacketGeneration();
        this.channel = channel;
    }

    /**
     * Set whether this replay sender operates in async mode.
     * When in async mode, it will send packets timed from a separate thread.
     * When not in async mode, it will send packets when {@link #sendPacketsTill(int)} is called.
     * @param asyncMode {@code true} to enable async mode
     */
    @Override
    public void setAsyncMode(boolean asyncMode) {
        synchronized (modeLock) {
            if (terminal.get() || this.asyncMode == asyncMode) return;
            controlEpoch.incrementAndGet();
            this.asyncMode = asyncMode;
            this.terminate = !asyncMode;
            long workerEpoch = ++asyncEpoch;
            if (asyncMode) {
                asyncWorker = new Thread(() -> runAsyncSender(workerEpoch), "replaymod-async-sender");
                asyncWorker.start();
            }
        }
    }

    @Override
    public boolean isAsyncMode() {
        return asyncMode;
    }

    /**
     * Set whether this replay sender  to operate in sync mode.
     * When in sync mode, it will send packets when {@link #sendPacketsTill(int)} is called.
     * This call will block until the async worker thread has stopped.
     */
    @Override
    public void setSyncModeAndWait() {
        Thread worker = asyncWorker;
        setAsyncMode(false);
        // Preserve this explicit mode-switch wait, but service runSync callbacks rather than
        // blocking the client on the sender monitor. Final termination never waits here.
        while (!terminal.get() && worker != null && worker.isAlive()) {
            executeTaskQueue();
            if (terminal.get()) return;
            try { Thread.sleep(0, 100_000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Return a fake system tile in milliseconds value that respects slowdown/speedup/pause and works in both,
     * sync and async mode.
     * Note: For sync mode this returns the last value passed to {@link #sendPacketsTill(int)}.
     * @return The timestamp in milliseconds since the start of the replay
     */
    @Override
    public int currentTimeStamp() {
        if (asyncMode && eofState == EofState.NONE && !paused()) {
            return (int) ((System.currentTimeMillis() - realTimeStart) * realTimeStartSpeed);
        } else {
            return lastTimeStamp;
        }
    }

    /**
     * Terminate this replay sender.
     */
    public void terminateReplay() {
        Throwable failure = null;
        try {
            try { stopResourceProduction(); }
            catch (RuntimeException | Error e) { failure = e; }
            if (terminationCleaned.compareAndSet(false, true)) {
                try { syncSender.shutdown(); }
                catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
                try { events.unregister(); }
                catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
                Channel ownedChannel = channel;
                if (ownedChannel != null) {
                    try { ownedChannel.pipeline().fireChannelInactive(); }
                    catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
                    try { ownedChannel.pipeline().close(); }
                    catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
                }
            }
        } finally {
            try { ReplayResourcePackReleaseBridge.requestRelease(resourcePackLease); }
            catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
            // Separate from terminationCleaned: a failed close/start remains retryable.
            try { requestPacketInputClose(); }
            catch (RuntimeException | Error e) { failure = collectFailure(failure, e); }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }

    /** Irreversible production stop, separate from both mode switching and general cleanup. */
    void stopResourceProduction() {
        synchronized (modeLock) {
            // Close the lease's publication/admission gate before exposing terminal=true;
            // otherwise a producer could publish in the gap between those two writes.
            try { resourcePackLease.stop(); }
            finally {
                terminal.set(true);
                terminate = true;
                ++asyncEpoch;
                if (asyncWorker != null) asyncWorker.interrupt();
            }
        }
    }

    boolean isTerminal() { return terminal.get(); }

    void advancePacketGeneration() {
        controlEpoch.incrementAndGet();
        if (!terminal.get()) resourcePackLease.advanceGeneration();
    }

    private static Throwable collectFailure(Throwable primary, Throwable next) {
        if (primary == null) return next;
        suppressOnce(primary, next);
        return primary;
    }

    /** Caller holds this; only a successful real close retires the reference. */
    private void closeReplayInputLocked() throws IOException {
        if (replayIn == null) return;
        replayInputClosePending = true;
        try {
            replayIn.close();
            replayIn = null;
            replayInputClosePending = false;
        } catch (IOException | RuntimeException | Error failure) {
            synchronized (packetCloseStateLock) { recordCloseFailureLocked(false, failure); }
            throw failure;
        }
    }

    /** Retire only the failed reader while still inside its own monitor acquisition. */
    private void retireFailedReplayInputLocked(Throwable primary) {
        restartPending = true;
        nextPacket = null;
        if (replayInputClosePending) return; // Do not spin/retry a just-failed close in the same read.
        try { closeReplayInputLocked(); }
        catch (IOException | RuntimeException | Error cleanup) { suppressOnce(primary, cleanup); }
    }

    private static void suppressOnce(Throwable primary, Throwable cleanup) {
        if (primary == null || cleanup == null || primary == cleanup) return;
        synchronized (primary) {
            for (Throwable existing : primary.getSuppressed()) if (existing == cleanup) return;
            primary.addSuppressed(cleanup);
        }
    }

    // Called only under packetCloseStateLock. Retain one failure per stage, not an unbounded chain.
    private void recordCloseFailureLocked(boolean file, Throwable failure) {
        if (file) {
            if (firstFileCloseFailure == null) firstFileCloseFailure = failure;
            suppressOnce(closePrimary, firstFileCloseFailure);
        } else {
            if (firstPacketCloseFailure == null) firstPacketCloseFailure = failure;
            suppressOnce(closePrimary, firstPacketCloseFailure);
        }
    }

    private void requestPacketInputClose() {
        requestClose(false, null);
    }

    /** Exit-only request from our Handler, never a synchronous release receipt. */
    void requestReplayFileClose(Throwable primary) {
        requestClose(true, primary);
    }

    private void requestClose(boolean file, Throwable primary) {
        try {
            synchronized (packetCloseStateLock) {
                if (primary != null) {
                    if (closePrimary == null) closePrimary = primary;
                    else suppressOnce(closePrimary, primary);
                    suppressOnce(closePrimary, firstPacketCloseFailure);
                    suppressOnce(closePrimary, firstFileCloseFailure);
                }
                ++packetCloseRequestSequence;
                packetInputCloseRequested = true;
                replayFileCloseRequested |= file;
                packetCloseStateLock.notifyAll();
                if (packetInputCloseCompleted && (!replayFileCloseRequested || replayFileCloseCompleted)) return;
                if (packetCloser == null || !packetCloser.isAlive()) {
                    // Keep even a failed-to-start thread reachable; a later request may retry startup.
                    packetCloser = new Thread(this::runPacketCloseWorker, "replaymod-packet-input-close");
                    packetCloser.setDaemon(true);
                    packetCloser.start();
                }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (packetCloseStateLock) { recordCloseFailureLocked(false, failure); }
            ReplayModReplay.LOGGER.error("Replay packet/file close worker startup failed; request retained, retry required", failure);
            throw failure;
        }
    }

    private void runPacketCloseWorker() {
        long delayMillis = 1000;
        while (true) {
            long sequence;
            boolean closeInput;
            boolean closeFile;
            boolean preserveRecovery;
            Throwable primary;
            synchronized (packetCloseStateLock) {
                sequence = packetCloseRequestSequence;
                closeInput = packetInputCloseRequested && !packetInputCloseCompleted;
                closeFile = replayFileCloseRequested && !replayFileCloseCompleted;
                primary = closePrimary;
                preserveRecovery = primary != null && !replayRecoveryIntentRegistered;
            }
            boolean inputCompleted = !closeInput;
            boolean fileCompleted = !closeFile;
            Throwable packetFailure = null;
            Throwable fileFailure = null;
            if (closeInput) {
                try {
                    // The actual reader barrier: no modeLock or short state lock is held here.
                    synchronized (this) {
                        nextPacket = null; // Terminal admission is already closed.
                        closeReplayInputLocked();
                    }
                    inputCompleted = true;
                } catch (IOException | RuntimeException | Error failure) { packetFailure = failure; }
            }
            if (closeFile) {
                try {
                    // Even a failed packet retirement must request adapter closing. No sender lock here.
                    if (replayFile instanceof ManagedReplayFile managed && preserveRecovery) {
                        managed.preserveRecovery(primary);
                        // Register the intent once: the adapter may append a failed record-write note.
                        // Repeating this on every close retry could grow primary.suppressed forever.
                        synchronized (packetCloseStateLock) { replayRecoveryIntentRegistered = true; }
                    }
                    replayFile.close();
                    fileCompleted = !(replayFile instanceof ManagedReplayFile managed) || managed.isReleased();
                } catch (IOException | RuntimeException | Error failure) { fileFailure = failure; }
            }
            synchronized (packetCloseStateLock) {
                if (inputCompleted) packetInputCloseCompleted = true;
                if (fileCompleted && closeFile) replayFileCloseCompleted = true;
                if (packetFailure != null) recordCloseFailureLocked(false, packetFailure);
                if (fileFailure != null) recordCloseFailureLocked(true, fileFailure);
                primary = closePrimary;
            }
            // Real diagnostics outside both locks; a recovery location is not proof protection succeeded.
            String recovery = null;
            if (packetFailure != null || fileFailure != null || (closeFile && !fileCompleted)) {
                recovery = closeRecoveryDescription();
            }
            if (packetFailure != null) ReplayModReplay.LOGGER.error(
                    "Replay packet close failed; retrying; recovery=" + recovery + "; business primary=" + primary, packetFailure);
            if (fileFailure != null) ReplayModReplay.LOGGER.error(
                    "Replay file close incomplete; retrying without discarding owner; recovery=" + recovery
                            + "; business primary=" + primary, fileFailure);
            if (closeFile && fileFailure == null && !fileCompleted) ReplayModReplay.LOGGER.warn(
                    "Replay file close pending actual Managed release; retrying; recovery=" + recovery
                            + "; business primary=" + primary);
            synchronized (packetCloseStateLock) {
                // Publishing requests and relinquishing responsibility use the same linearization lock.
                if (sequence != packetCloseRequestSequence) {
                    delayMillis = 1000;
                    continue;
                }
                if (packetInputCloseCompleted && (!replayFileCloseRequested || replayFileCloseCompleted)) {
                    packetCloser = null;
                    return;
                }
                long deadline = System.nanoTime() + delayMillis * 1_000_000L;
                while (sequence == packetCloseRequestSequence) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        packetCloseStateLock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                    } catch (InterruptedException ignored) {
                        // This daemon owns retry responsibility; interrupt is not a release receipt.
                        break;
                    }
                }
            }
            delayMillis = Math.min(30_000, delayMillis * 2);
        }
    }

    private String closeRecoveryDescription() {
        if (!(replayFile instanceof ManagedReplayFile managed)) return "non-Managed (close return only)";
        try { return String.valueOf(managed.recoveryRecord()); }
        catch (IOException | RuntimeException | Error failure) { return "unavailable: " + failure; }
    }

    private class EventHandler extends EventRegistrations {
        { on(PreTickCallback.EVENT, this::onWorldTick); }
        private void onWorldTick() {
            // Spawning a player into an empty chunk (which we might do with the recording player)
            // prevents it from being moved by teleport packets (it essentially gets stuck) because
            // Entity#addedToChunk is not set and it is therefore not updated every tick.
            // To counteract this, we need to manually update it's position if it hasn't been added
            // to any chunk yet.
            // The `updateNeeded` flag appears to have been removed in 1.17, so this should no longer be an issue.
            //#if MC<11700
            //$$ if (mc.world != null) {
            //$$     for (PlayerEntity playerEntity : mc.world.getPlayers()) {
            //$$         if (!playerEntity.updateNeeded && playerEntity instanceof OtherClientPlayerEntity) {
            //$$            playerEntity.tickMovement();
            //$$         }
            //$$     }
            //$$ }
            //#endif
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        // When in async mode and the replay sender shut down, then don't send packets
        if (terminal.get() || ctx.channel() != channel || (terminate && asyncMode)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        if (msg instanceof Packet) {
            try {
                Packet p = (Packet) msg;

                if (p != null) {
                    p = processPacket(p);
                    if (p != null) {
                        if (terminal.get() || ctx.channel() != channel) {
                            ReferenceCountUtil.release(p);
                            return;
                        }
                        super.channelRead(ctx, p);
                    }

                    maybeRemoveDeadEntities(p);

                    //#if MC>=11400
                    if (p instanceof ChunkDataS2CPacket) {
                        Runnable doLightUpdates = () -> {
                            ClientWorld world = mc.world;
                            if (world != null) {
                                //#if MC>=11800
                                while (!world.hasNoChunkUpdaters()) {
                                    world.runQueuedChunkUpdates();
                                }
                                //#endif
                                LightingProvider provider = world.getChunkManager().getLightingProvider();
                                while (provider.hasUpdates()) {
                                    //#if MC>=12000
                                    provider.doLightUpdates();
                                    //#else
                                    //$$ provider.doLightUpdates(Integer.MAX_VALUE, true, true);
                                    //#endif
                                }
                            }
                        };
                        if (mc.isOnThread()) {
                            guardPacketCallback(doLightUpdates).run();
                        } else {
                            mc.send(guardPacketCallback(doLightUpdates));
                        }
                    }
                    //#endif
                }
            } catch (Exception e) {
                // We'd rather not have a failure parsing one packet screw up the whole replay process
                e.printStackTrace();
            }
        }

    }

    // If we do not give minecraft time to tick, there will be dead entity artifacts left in the world
    // Therefore we have to remove all loaded, dead entities manually if we are in sync mode.
    // We do this after every SpawnX packet and after the destroy entities packet.
    private void maybeRemoveDeadEntities(Packet packet) {
        if (asyncMode) {
            return; // MC should have enough time to tick
        }

        boolean relevantPacket = packet instanceof EntitySpawnS2CPacket
                //#if MC<12002
                //$$ || packet instanceof PlayerSpawnS2CPacket
                //#endif
                //#if MC<11900
                //$$ || packet instanceof MobSpawnS2CPacket
                //$$ || packet instanceof PaintingSpawnS2CPacket
                //#endif
                //#if MC<11600
                //$$ || packet instanceof EntitySpawnGlobalS2CPacket
                //#endif
                || packet instanceof ExperienceOrbSpawnS2CPacket
                || packet instanceof EntitiesDestroyS2CPacket;
        if (!relevantPacket) {
            return; // don't want to do it too often, only when there's likely to be a dead entity
        }

        mc.send(guardPacketCallback(() -> {
            ClientWorld world = mc.world;
            if (world != null) {
                removeDeadEntities(world);
            }
        }));
    }

    private void removeDeadEntities(ClientWorld world) {
        //#if MC>=11700
        // From the looks of it, this has now been resolved (thanks to EntityChangeListener)
        //#elseif MC>=11400
        //$$ // Note: Not sure if it's still required but there's this really handy method anyway
        //$$ world.finishRemovingEntities();
        //#else
        //$$ Iterator<Entity> iter = world.loadedEntityList.iterator();
        //$$ while (iter.hasNext()) {
        //$$     Entity entity = iter.next();
        //$$     if (entity.isDead) {
        //$$         int chunkX = entity.chunkCoordX;
        //$$         int chunkY = entity.chunkCoordZ;
        //$$
                //#if MC>=11400
                //$$ if (entity.addedToChunk && world.getChunkProvider().provideChunk(chunkX, chunkY, false, false) != null) {
                //#else
                //#if MC>=10904
                //$$ if (entity.addedToChunk && world.getChunkProvider().getLoadedChunk(chunkX, chunkY) != null) {
                //#else
                //$$ if (entity.addedToChunk && world.getChunkProvider().chunkExists(chunkX, chunkY)) {
                //#endif
                //#endif
        //$$             world.getChunkFromChunkCoords(chunkX, chunkY).removeEntity(entity);
        //$$         }
        //$$
        //$$         iter.remove();
        //$$         world.onEntityRemoved(entity);
        //$$     }
        //$$
        //$$ }
        //#endif
    }

    /**
     * Process a packet and return the result.
     * @param p The packet to process
     * @return The processed packet or {@code null} if no packet shall be sent
     */
    protected Packet processPacket(Packet p) throws Exception {
        if (terminal.get()) return null;
        if (p instanceof LoginSuccessS2CPacket) {
            registry = registry.withLoginSuccess();
            return p;
        }
        //#if MC>=12002
        if (p instanceof ReadyS2CPacket) {
            registry = registry.withState(State.PLAY);
            return p;
        }
        if (p instanceof EnterReconfigurationS2CPacket) {
            registry = registry.withState(State.CONFIGURATION);
            hasWorldLoaded = false;
            return p;
        }
        //#endif

        if (p instanceof CustomPayloadS2CPacket) {
            CustomPayloadS2CPacket packet = (CustomPayloadS2CPacket) p;
            if (Restrictions.PLUGIN_CHANNEL.equals(packet.payload().getId().id())) {
                final String unknown = replayHandler.getRestrictions().handle(packet);
                if (unknown == null) {
                    return null;
                } else {
                    // Failed to parse options, make sure that under no circumstances further packets are parsed
                    terminateReplay();
                    // Then end replay and show error GUI
                    ServerResourcePackLoader loader = mc.getServerResourcePackProvider();
                    long connectionEpoch = ReplayResourcePackReleaseBridge.connectionEpoch(loader);
                    ReplayMod.instance.runLater(() -> {
                        if (!replayHandler.ownsFullReplaySender(this)
                                || ReplayResourcePackReleaseBridge.connectionEpoch(loader) != connectionEpoch) return;
                        try {
                            replayHandler.endReplay();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        if (ReplayModReplay.instance.getReplayHandler() != null) return;
                        mc.setScreen(new NoticeScreen(
                                //#if MC>=11400
                                () -> mc.setScreen(null),
                                net.minecraft.text.Text.translatable("replaymod.error.unknownrestriction1"),
                                net.minecraft.text.Text.translatable("replaymod.error.unknownrestriction2", unknown)
                                //#else
                                //$$ I18n.format("replaymod.error.unknownrestriction1"),
                                //$$ I18n.format("replaymod.error.unknownrestriction2", unknown)
                                //#endif
                        ));
                    });
                }
            }
        }
        if (terminal.get()) return null;
        if (p instanceof DisconnectS2CPacket) {
            Text reason = ((DisconnectS2CPacket) p).reason();
            String message = reason.getString();
            if ("Please update to view this replay.".equals(message)) {
                // This version of the mod supports replay restrictions so we are allowed
                // to remove this packet.
                return null;
            }
        }

        if(BAD_PACKETS.contains(p.getClass())) return null;

        if (p instanceof CustomPayloadS2CPacket) {
            CustomPayloadS2CPacket packet = (CustomPayloadS2CPacket) p;
            //#if MC>=11400
            Identifier channelName = packet.payload().getId().id();
            //#else
            //$$ String channelName = packet.getChannelName();
            //#endif
            String channelNameStr = channelName.toString();

            if (channelNameStr.startsWith("fabric-screen-handler-api-v")) {
                return null; // we do not want to show modded screens which got opened for the recording player
            }

            //noinspection UnstableApiUsage
            if(packet.payload() instanceof AdvancedOpenScreenPayload) {
                return null;
            }

            // On 1.14+ there's a dedicated OpenWrittenBookS2CPacket now
            //#if MC<11400
            //#if MC>=11400
            //$$ if (SPacketCustomPayload.BOOK_OPEN.equals(channelName)) {
            //#else
            //$$ if ("MC|BOpen".equals(channelName)) {
            //#endif
            //$$     return null;
            //$$ }
            //#endif
        //#if MC>=10800
        }

        if(p instanceof ResourcePackSendS2CPacket) {
            ResourcePackSendS2CPacket packet = (ResourcePackSendS2CPacket) p;
            //#if MC>=12003
            String url = packet.url();
            //#else
            //$$ String url = packet.getURL();
            //#endif
            if (url.startsWith("replay://")) {
        //#else
        //$$     String url;
        //$$     if ("MC|RPack".equals(channelName) &&
        //$$             (url = new String(packet.func_149168_d(), Charsets.UTF_8)).startsWith("replay://")) {
        //#endif
                // A replay URL is always consumed here, including invalid/missing/failed packs.
                // It must never fall back to vanilla's URL downloader with a partial ZIP.
                sendResourcePack(url, packet.id());
                return null;
            }
        }

        if(p instanceof GameJoinS2CPacket) {
            GameJoinS2CPacket packet = (GameJoinS2CPacket) p;
            int entId = packet.playerEntityId();
            schedulePacketHandler(() -> allowMovement = true);
            actualID = entId;
            entId = -1789435; // Camera entity id should be negative which is an invalid id and can't be used by servers
            //#if MC>=11400
            p = new GameJoinS2CPacket(
                    entId,
                    //#if MC>=12002
                    packet.hardcore(),
                    packet.dimensionIds(),
                    packet.maxPlayers(),
                    packet.viewDistance(),
                    packet.simulationDistance(),
                    packet.reducedDebugInfo(),
                    packet.showDeathScreen(),
                    packet.doLimitedCrafting(),
                    withSpectatorMode(packet.commonPlayerSpawnInfo())
                    //#if MC>=12006
                    , packet.enforcesSecureChat()
                    //#endif
                    //#else
                    //#if MC>=11800
                    //$$ packet.hardcore(),
                    //#endif
                    //$$ GameMode.SPECTATOR,
                    //#if MC>=11600
                    //$$ GameMode.SPECTATOR,
                    //#endif
                    //#if MC<11800
                    //#if MC>=11500
                    //$$ packet.getSha256Seed(),
                    //#endif
                    //$$ false,
                    //#endif
                    //#if MC>=11600
                    //#if MC>=11603
                    //$$ packet.dimensionIds(),
                    //#if MC>=11800
                    //$$ packet.registryManager(),
                    //#else
                    //$$ (net.minecraft.util.registry.DynamicRegistryManager.Impl) packet.getRegistryManager(),
                    //#endif
                    //$$ packet.dimensionType(),
                    //#else
                    //$$ packet.method_29443(),
                    //$$ (net.minecraft.util.registry.RegistryTracker.Modifiable) packet.getDimension(),
                    //$$ packet.method_29444(),
                    //#endif
                    //$$ packet.dimensionId(),
                    //#else
                    //$$ packet.getDimension(),
                    //#endif
                    //#if MC>=11800
                    //$$ packet.sha256Seed(),
                    //#endif
                    //$$ 0, // max players (has no getter -> never actually used)
                    //#if MC<11600
                    //$$ packet.getGeneratorType(),
                    //#endif
                    //$$ packet.viewDistance(),
                    //#if MC>=11800
                    //$$ packet.simulationDistance(),
                    //#endif
                    //$$ packet.reducedDebugInfo()
                    //#if MC>=11500
                    //$$ , packet.showDeathScreen()
                    //#endif
                    //#if MC>=11600
                    //$$ , packet.debugWorld()
                    //$$ , packet.flatWorld()
                    //#endif
                    //#if MC>=11900
                    //$$ , java.util.Optional.empty()
                    //#endif
                    //#if MC>=12000
                    //$$, packet.portalCooldown()
                    //#endif
                    //#endif
            );
            //#else
            //#if MC>=10800
            //#if MC>=11400
            //$$ DimensionType dimension = packet.func_212642_e();
            //#else
            //$$ int dimension = packet.getDimension();
            //#endif
            //$$ EnumDifficulty difficulty = packet.getDifficulty();
            //#if MC>=11400
            //$$ int maxPlayers = 0; // literally never used by vanilla (i.e. no accessor)
            //#else
            //$$ int maxPlayers = packet.getMaxPlayers();
            //#endif
            //$$ WorldType worldType = packet.getWorldType();
            //$$
            //#if MC>=10904
            //$$ p = new SPacketJoinGame(entId, GameType.SPECTATOR, false, dimension,
            //$$         difficulty, maxPlayers, worldType, false);
            //#else
            //$$ p = new S01PacketJoinGame(entId, GameType.SPECTATOR, false, dimension,
            //$$         difficulty, maxPlayers, worldType, false);
            //#endif
            //#else
            //$$ int dimension = packet.func_149194_f();
            //$$ EnumDifficulty difficulty = packet.func_149192_g();
            //$$ int maxPlayers = packet.func_149193_h();
            //$$ WorldType worldType = packet.func_149196_i();
            //$$
            //$$ p = new S01PacketJoinGame(entId, GameType.ADVENTURE, false, dimension,
            //$$         difficulty, maxPlayers, worldType);
            //#endif
            //#endif
        }

        if(p instanceof PlayerRespawnS2CPacket) {
            PlayerRespawnS2CPacket respawn = (PlayerRespawnS2CPacket) p;
            //#if MC>=11400
            p = new PlayerRespawnS2CPacket(
                    //#if MC>=12002
                    withSpectatorMode(respawn.commonPlayerSpawnInfo()),
                    (byte) 0
                    //#else
                    //#if MC>=11600
                    //$$ respawn.getDimensionType(),
                    //#endif
                    //$$ respawn.getDimension(),
                    //#if MC>=11500
                    //$$ respawn.getSha256Seed(),
                    //#endif
                    //#if MC>=11600
                    //$$ GameMode.SPECTATOR,
                    //$$ GameMode.SPECTATOR,
                    //$$ respawn.isDebugWorld(),
                    //$$ respawn.isFlatWorld(),
                    //#if MC>=11903
                    //$$ (byte) 0
                    //#else
                    //$$ false
                    //#endif
                    //#else
                    //$$ respawn.getGeneratorType(),
                    //$$ GameMode.SPECTATOR
                    //#endif
                    //#if MC>=11900
                    //$$ , java.util.Optional.empty()
                    //#endif
                    //#if MC>=12000
                    //$$, respawn.getPortalCooldown()
                    //#endif
                    //#endif
            );
            //#else
            //#if MC>=10809
            //$$ p = new SPacketRespawn(respawn.getDimensionID(),
            //$$         respawn.getDifficulty(), respawn.getWorldType(), GameType.SPECTATOR);
            //#else
            //$$ p = new S07PacketRespawn(respawn.func_149082_c(),
            //$$         respawn.func_149081_d(), respawn.func_149080_f(),
                    //#if MC>=10800
                    //$$ GameType.SPECTATOR);
                    //#else
                    //$$ GameType.ADVENTURE);
                    //#endif
            //#endif
            //#endif

            schedulePacketHandler(() -> allowMovement = true);
        }

        if(p instanceof PlayerPositionLookS2CPacket) {
            final PlayerPositionLookS2CPacket ppl = (PlayerPositionLookS2CPacket) p;
            if(!hasWorldLoaded) hasWorldLoaded = true;

            ReplayMod.instance.runLater(guardControlCallback(() -> {
                if (mc.currentScreen instanceof DownloadingTerrainScreen) {
                    // Close the world loading screen manually in case we swallow the packet
                    mc.setScreen(null);
                }
            }));

            if(replayHandler.shouldSuppressCameraMovements()) return null;

            //#if MC>=10800
            //#if MC>=11904
            for (PositionFlag relative : ppl.getFlags()) {
                if (relative == PositionFlag.X || relative == PositionFlag.Y || relative == PositionFlag.Z) {
            //#elseif MC>=11400
            //$$ for (PlayerPositionLookS2CPacket.Flag relative : ppl.getFlags()) {
            //$$     if (relative == PlayerPositionLookS2CPacket.Flag.X
            //$$             || relative == PlayerPositionLookS2CPacket.Flag.Y
            //$$             || relative == PlayerPositionLookS2CPacket.Flag.Z) {
            //#else
            //#if MC>=10904
            //$$ for (SPacketPlayerPosLook.EnumFlags relative : ppl.getFlags()) {
            //#else
            //$$ for (Object relative : ppl.func_179834_f()) {
            //#endif
            //$$     if (relative == SPacketPlayerPosLook.EnumFlags.X
            //$$             || relative == SPacketPlayerPosLook.EnumFlags.Y
            //$$             || relative == SPacketPlayerPosLook.EnumFlags.Z) {
            //#endif
                    return null; // At least one of the coordinates is relative, so we don't care
                }
            }
            //#endif

            scheduleControlHandler(new Runnable() {
                private final long intention = controlEpoch.get();
                private final long generation = packetGeneration();
                private final ServerResourcePackLoader loader = mc.getServerResourcePackProvider();
                private final long connectionEpoch = ReplayResourcePackReleaseBridge.connectionEpoch(loader);
                @Override
                @SuppressWarnings("unchecked")
                public void run() {
                    if (controlEpoch.get() != intention || !callbackCurrent(generation, loader, connectionEpoch)) return;
                    // FIXME: world shouldn't ever be null at this point, now that we use the packet queue
                    //        probably fine to remove on the next non-patch version (don't want to break stuff now)
                    if (mc.world == null || !mc.isOnThread()) {
                        ReplayMod.instance.runLater(this);
                        return;
                    }

                    CameraEntity cent = replayHandler.getCameraEntity();
                    if (!allowMovement && !((Math.abs(cent.getX() - ppl.getX()) > TP_DISTANCE_LIMIT) ||
                            (Math.abs(cent.getZ() - ppl.getZ()) > TP_DISTANCE_LIMIT))) {
                        return;
                    } else {
                        allowMovement = false;
                    }
                    cent.setCameraPosition(ppl.getX(), ppl.getY(), ppl.getZ());
                    cent.setCameraRotation(ppl.getYaw(), ppl.getPitch(), cent.roll);
                }
            });

            return null;
        }

        if(p instanceof GameStateChangeS2CPacket) {
            GameStateChangeS2CPacket pg = (GameStateChangeS2CPacket)p;
            // only allow the following packets:
            // 1 - End raining
            // 2 - Begin raining
            //
            // The following values are to control sky color (e.g. if thunderstorm)
            // 7 - Fade value
            // 8 - Fade time
            if (!Arrays.asList(
                    //#if MC>=11600
                    GameStateChangeS2CPacket.RAIN_STARTED,
                    GameStateChangeS2CPacket.RAIN_STOPPED,
                    GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED,
                    GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED
                    //#else
                    //$$ 1,
                    //$$ 2,
                    //$$ 7,
                    //$$ 8
                    //#endif
            ).contains(pg.getReason())) {
                return null;
            }
        }

        //#if MC>=11903
        if (p instanceof GameMessageS2CPacket || p instanceof ChatMessageS2CPacket || p instanceof ProfilelessChatMessageS2CPacket) {
        //#elseif MC==11901 || MC==11902
        //$$ if (p instanceof GameMessageS2CPacket || p instanceof ChatMessageS2CPacket || p instanceof MessageHeaderS2CPacket) {
        //#elseif MC>=11900
        //$$ if (p instanceof GameMessageS2CPacket || p instanceof ChatMessageS2CPacket) {
        //#else
        //$$ if (p instanceof GameMessageS2CPacket) {
        //#endif
            if (!ReplayModReplay.instance.getCore().getSettingsRegistry().get(Setting.SHOW_CHAT)) {
                return null;
            }
        }

        if (asyncMode) {
            return processPacketAsync(p);
        } else {
            Packet fp = p;
            mc.send(guardPacketCallback(() -> processPacketSync(fp)));
            return p;
        }
    }

    //#if MC>=12002
    private CommonPlayerSpawnInfo withSpectatorMode(CommonPlayerSpawnInfo org) {
        return new CommonPlayerSpawnInfo(
                org.dimensionType(),
                org.dimension(),
               org.seed(),
                GameMode.SPECTATOR,
                GameMode.SPECTATOR,
                org.isDebug(),
                org.isFlat(),
                org.lastDeathLocation(),
                org.portalCooldown()
         );
    }
    //#endif

    /**
     * Returns the speed of the replay. 1 being normal speed, 0.5 half and 2 twice as fast.
     * If 0 is returned, the replay is paused.
     * @return speed multiplier
     */
    @Override
    public double getReplaySpeed() {
        if(!paused()) return replaySpeed;
        else return 0;
    }

    /**
     * Set the speed of the replay. 1 being normal speed, 0.5 half and 2 twice as fast.
     * The speed may not be set to 0 nor to negative values.
     * @param d Speed multiplier
     */
    @Override
    public void setReplaySpeed(final double d) {
        synchronized (modeLock) {
            if (terminal.get()) return;
            if (d != 0) {
                this.replaySpeed = d;
                this.realTimeStartSpeed = d;
                this.realTimeStart = System.currentTimeMillis() - (long) (lastTimeStamp / d);
            }
            TimerAccessor timer = (TimerAccessor) ((MinecraftAccessor) mc).getTimer();
            //#if MC>=11200
            timer.setTickLength(DEFAULT_MS_PER_TICK / (float) d);
            //#else
            //$$ timer.setTimerSpeed((float) d);
            //#endif
            if (eofState != EofState.NONE) {
                eofState = d > 0 ? EofState.RESUME : EofState.PAUSED;
            }
        }
    }

    @Override
    public boolean paused() {
        EofState state = eofState;
        // The overlay must offer Play even if the client has not processed the EOF pause yet.
        return (asyncMode && (state == EofState.NEEDS_PAUSE || state == EofState.PAUSED))
                || ReplaySender.super.paused();
    }

    /////////////////////////////////////////////////////////
    //       Asynchronous packet processing                //
    /////////////////////////////////////////////////////////

    /**
     * Timestamp in milliseconds of when we started (or would have started when taking pauses and speed into account)
     * the playback of the replay.
     * Updated only when replay speed changes or on pause/unpause but definitely not on every packet to prevent gradual
     * drifting.
     */
    private long realTimeStart;

    /**
     * The replay speed used for {@link #realTimeStart}.
     * If the target speed differs from this one, the timestamp is recalculated.
     */
    private double realTimeStartSpeed;

    /**
     * There is no waiting performed until a packet with at least this timestamp is reached (but not yet sent).
     * If this is -1, then timing is normal.
     */
    private volatile long desiredTimeStamp = -1;

    /**
     * Runnable which performs timed dispatching of packets from the input stream.
     */
    private boolean asyncStopped(long workerEpoch) {
        return terminal.get() || terminate || workerEpoch != asyncEpoch;
    }

    private void runAsyncSender(long workerEpoch) {
            try {
                REPLAY_LOOP:
                while (!asyncStopped(workerEpoch)) {
                    synchronized (FullReplaySender.this) {
                        try {
                        if (asyncStopped(workerEpoch)) return;
                        checkInputGenerationLocked();
                        if (eofState != EofState.NONE && !awaitEofRestartLocked(workerEpoch)) break;
                        if (startFromBeginning || restartPending || eofState != EofState.NONE) {
                            if (!restartInputLocked(workerEpoch, true) && !asyncStopped(workerEpoch)) {
                                Thread.sleep(replayInputClosePending ? 1000 : 100);
                            }
                            continue;
                        }
                        if (asyncStopped(workerEpoch)) return;
                        if (!openReplayInputLocked()) continue;
                        // Packet loop
                        while (true) {
                            try {
                                // When playback is paused and the world has loaded (we don't want any dirt-screens) we sleep
                                while (paused() && hasWorldLoaded && !inBundle) {
                                    // Unless we are going to terminate, restart or jump
                                    if (asyncStopped(workerEpoch) || startFromBeginning || desiredTimeStamp != -1) {
                                        break;
                                    }
                                    Thread.sleep(10);
                                }

                                if (terminal.get() || (asyncStopped(workerEpoch) && !inBundle)) {
                                    break REPLAY_LOOP;
                                }

                                if (startFromBeginning) {
                                    // In case we need to restart from the beginning
                                    // break out of the loop sending all packets which will
                                    // cause the replay to be restarted by the outer loop
                                    break;
                                }

                                // Read the next packet if we don't already have one
                                if (nextPacket == null) {
                                    nextPacket = new FullReplaySender.PacketData(replayIn, replayInGeneration);
                                }

                                int nextTimeStamp = nextPacket.timestamp;

                                // If we aren't jumping and the world has already been loaded (no dirt-screens) then wait
                                // the required amount to get proper packet timing
                                if (!isHurrying() && hasWorldLoaded && !inBundle) {
                                    // Timestamp of when the next packet should be sent
                                    long expectedTime = realTimeStart + (long) (nextTimeStamp / replaySpeed);
                                    long now = System.currentTimeMillis();
                                    // If the packet should not yet be sent, wait a bit
                                    if (expectedTime > now) {
                                        Thread.sleep(expectedTime - now);
                                    }
                                }

                                if (terminal.get() || (asyncStopped(workerEpoch) && !inBundle)) break REPLAY_LOOP;
                                if (startFromBeginning) break; // A backward seek may have arrived while sleeping.
                                // Process packet
                                if (nextPacket.type == PacketType.Bundle) inBundle = !inBundle;
                                dispatchPacket(nextPacket);
                                nextPacket = null;

                                lastTimeStamp = nextTimeStamp;

                                // MC as of 1.20.2 relies on autoRead, so it can update the connection state on the main
                                // thread before the next packet is read. As such, we need to stall if that was just
                                // enabled.
                                // Might be safe to do the same on older versions too, but I'd rather not poke the
                                // monster that is Forge networking.
                                //#if MC>=12002
                                while (!channel.config().isAutoRead()) {
                                    if (terminal.get()) break REPLAY_LOOP;
                                    Thread.sleep(0, 100_000);
                                }
                                //#endif

                                // In case we finished jumping
                                // We need to check that we aren't planing to restart so we don't accidentally run this
                                // code before we actually restarted
                                long completedIntention = -1;
                                synchronized (modeLock) {
                                    if (isHurrying() && lastTimeStamp > desiredTimeStamp && !startFromBeginning) {
                                        desiredTimeStamp = -1;
                                        completedIntention = controlEpoch.get();
                                    }
                                }
                                if (completedIntention != -1) {
                                    moveCameraAfterHurry(completedIntention);
                                    // Do not recapture a newer jump's intention after queuing the camera callback.
                                    pauseAsync(workerEpoch, completedIntention);
                                }
                            } catch (EOFException eof) {
                                markEofLocked();
                                tryCloseEofInputLocked();
                                break;
                            }
                        }
                        } catch (Exception | Error failure) {
                            // A superseded/mode-switched worker must not retire the handoff stream.
                            if (!(failure instanceof InterruptedException && asyncStopped(workerEpoch))) {
                                retireFailedReplayInputLocked(failure);
                            }
                            throw failure;
                        }
                    }
                }
            } catch (Exception e) {
                if (!(e instanceof InterruptedException && asyncStopped(workerEpoch))) {
                    ReplayModReplay.LOGGER.error("Replay async reader stopped; retirement pending if close failed", e);
                }
            } catch (Error e) {
                ReplayModReplay.LOGGER.error("Replay async reader failed; retirement pending if close failed", e);
                throw e;
            } finally {
                // Ordinary mode/epoch handoff keeps legal prefetch and its stream untouched.
                if (terminal.get()) requestPacketInputClose();
            }
    }

    /**
     * Return whether this replay sender is currently rushing. When rushing, all packets are sent without waiting until
     * a specified timestamp is passed.
     * @return {@code true} if currently rushing, {@code false} otherwise
     */
    public boolean isHurrying() {
        return desiredTimeStamp != -1;
    }

    /**
     * Cancels the hurrying.
     */
    public void stopHurrying() {
        synchronized (modeLock) {
            controlEpoch.incrementAndGet();
            desiredTimeStamp = -1;
        }
    }

    /**
     * Return the timestamp to which this replay sender is currently rushing. All packets with an lower or equal
     * timestamp will be sent out without any sleeping.
     * @return The timestamp in milliseconds since the start of the replay
     */
    public long getDesiredTimestamp() {
        return desiredTimeStamp;
    }

    /**
     * Jumps to the specified timestamp when in async mode by rushing all packets until one with a timestamp greater
     * than the specified timestamp is found.
     * If the timestamp has already passed, this causes the replay to restart and then rush all packets.
     * @param millis Timestamp in milliseconds since the start of the replay
     */
    @Override
    public void jumpToTime(int millis) {
        synchronized (modeLock) {
            if (terminal.get()) return;
            Preconditions.checkState(asyncMode, "Can only jump in async mode. Use sendPacketsTill(int) instead.");
            controlEpoch.incrementAndGet();
            if (millis < lastTimeStamp) {
                // Discard old-stream work immediately, including already queued resource requests.
                resourcePackLease.advanceGeneration();
                startFromBeginning = true;
            }
            desiredTimeStamp = millis;
        }
    }

    protected Packet processPacketAsync(Packet p) {
        //If hurrying, ignore some packets, except for short durations
        if(desiredTimeStamp - lastTimeStamp > 1000) {
            if(p instanceof ParticleS2CPacket) return null;

            if(p instanceof EntitySpawnS2CPacket) {
                EntitySpawnS2CPacket pso = (EntitySpawnS2CPacket)p;
                //#if MC>=11400
                if (pso.getEntityType() == EntityType.FIREWORK_ROCKET) return null;
                //#else
                //$$ int type = pso.getType();
                //$$ if(type == 76) { // Firework rocket
                //$$     return null;
                //$$ }
                //#endif
            }
        }
        return p;
    }

    /////////////////////////////////////////////////////////
    //        Synchronous packet processing                //
    /////////////////////////////////////////////////////////

    // Even in sync mode, we send from another thread because mods may rely on that
    private final ExecutorService syncSender = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "replaymod-sync-sender"));

    /**
     * Sends all packets until the specified timestamp is reached (inclusive).
     * If the timestamp is smaller than the last packet sent, the replay is restarted from the beginning.
     * @param timestamp The timestamp in milliseconds since the beginning of this replay
     */
    @Override
    public void sendPacketsTill(int timestamp) {
        if (terminal.get()) return;
        Preconditions.checkState(!asyncMode, "This method cannot be used in async mode. Use jumpToTime(int) instead.");

        // Submit our target to the sender thread and track its progress
        AtomicBoolean doneSending = new AtomicBoolean();
        long generation = resourcePackLease.generation();
        long intention = controlEpoch.get();
        long workerEpoch = asyncEpoch;
        try {
        syncSender.submit(() -> {
            try {
                if (!terminal.get() && !asyncMode && asyncEpoch == workerEpoch
                        && controlEpoch.get() == intention && resourcePackLease.generation() == generation) {
                    doSendPacketsTill(timestamp, workerEpoch);
                }
            } finally {
                doneSending.set(true);
            }
        });
        } catch (RejectedExecutionException rejected) {
            if (terminal.get()) return;
            throw rejected;
        }

        // Drain the task queue while we are sending (in case a mod blocks the io thread waiting for the main thread)
        while (!doneSending.get()) {
            if (terminal.get()) return;
            executeTaskQueue();
            if (terminal.get()) return;

            // Wait until the sender thread has made progress
            try {
                //noinspection BusyWait
                Thread.sleep(0, 100_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Everything has been sent, drain the queue one last time unless the session ended.
        if (!terminal.get()) executeTaskQueue();
    }

    private void doSendPacketsTill(int timestamp, long workerEpoch) {
        try {
            synchronized (this) {
                try {
                if (!restartOwnerCurrent(workerEpoch, false)) return;
                checkInputGenerationLocked();
                if (!restartPending && eofState != EofState.NONE && timestamp >= lastTimeStamp) {
                    // A sync query at/after the end keeps the last frame and the requested clock.
                    updateSyncClock(timestamp);
                    tryCloseEofInputLocked();
                    return;
                }
                if (timestamp == lastTimeStamp && !restartPending) return;
                if (restartPending || timestamp < lastTimeStamp) {
                    restartPending = true;
                    if (!restartInputLocked(workerEpoch, false)) return;
                }

                synchronized (modeLock) {
                    if (!restartOwnerCurrent(workerEpoch, false)) return;
                    // Sync's first genuine read already uses Handler.setup's LOGIN channel.
                    // A later ordinary async handoff must not mistake it for bootstrap.
                    startFromBeginning = false;
                }
                if (!openReplayInputLocked()) return;

                while (!terminal.get()) { // Send packets
                    try {
                        PacketData pd;
                        if (nextPacket != null) {
                            // If there is still a packet left from before, use it first
                            pd = nextPacket;
                            nextPacket = null;
                        } else {
                            // Otherwise read one from the input stream
                            pd = new PacketData(replayIn, replayInGeneration);
                        }

                        int nextTimeStamp = pd.timestamp;
                        if (nextTimeStamp > timestamp && !inBundle) {
                            // We are done sending all packets
                            nextPacket = pd;
                            break;
                        }

                        if (terminal.get()) return;
                        // Process packet
                        if (pd.type == PacketType.Bundle) inBundle = !inBundle;
                        dispatchPacket(pd);

                        // MC as of 1.20.2 relies on autoRead, so it can update the connection state on the main
                        // thread before the next packet is read. As such, we need to stall if that was just
                        // enabled.
                        // Might be safe to do the same on older versions too, but I'd rather not poke the
                        // monster that is Forge networking.
                        //#if MC>=12002
                        while (!channel.config().isAutoRead()) {
                            if (terminal.get()) return;
                            Thread.sleep(0, 100_000);
                        }
                        //#endif
                    } catch (EOFException eof) {
                        updateSyncClock(timestamp);
                        markEofLocked();
                        tryCloseEofInputLocked();
                        return;
                    }
                }

                updateSyncClock(timestamp);
                } catch (Exception | Error failure) {
                    if (!(failure instanceof InterruptedException && terminal.get())) {
                        retireFailedReplayInputLocked(failure);
                    }
                    throw failure;
                }
            }
        } catch (Exception e) {
            ReplayModReplay.LOGGER.error("Replay sync reader stopped; retirement pending if close failed", e);
        } catch (Error e) {
            ReplayModReplay.LOGGER.error("Replay sync reader failed; retirement pending if close failed", e);
            throw e;
        }
    }

    private void executeTaskQueue() {
        //#if MC>=11400
        ((MCVer.MinecraftMethodAccessor) mc).replayModExecuteTaskQueue();
        //#else
        //$$ java.util.Queue<java.util.concurrent.FutureTask<?>> scheduledTasks = ((MinecraftAccessor) mc).getScheduledTasks();
        //$$
        //$$ // Live-lock detection: if we already hold the lock, then the sender thread will never be able to queue its
        //$$ // tasks
        //$$ if (Thread.holdsLock(scheduledTasks)) {
        //$$     throw new IllegalStateException("Task queue already locked. " +
        //$$             "You may want to use `Scheduler.runLaterWithoutLock` to run while the lock is not taken.");
        //$$ }
        //$$
        //$$ //noinspection SynchronizationOnLocalVariableOrMethodParameter
        //$$ synchronized (scheduledTasks) {
        //$$     while (!scheduledTasks.isEmpty()) {
        //$$         scheduledTasks.poll().run();
        //$$     }
        //$$ }
        //#endif
        ReplayMod.instance.runTasks();
    }

    private long packetGeneration() {
        Long generation = dispatchGeneration.get();
        return generation == null ? resourcePackLease.generation() : generation;
    }

    private void dispatchPacket(PacketData packet) {
        if (terminal.get() || packet.generation != resourcePackLease.generation()) return;
        Long previous = dispatchGeneration.get();
        dispatchGeneration.set(packet.generation);
        try { channel.pipeline().fireChannelRead(Unpooled.wrappedBuffer(packet.bytes)); }
        finally {
            if (previous == null) dispatchGeneration.remove();
            else dispatchGeneration.set(previous);
        }
    }

    private void sendResourcePack(String replayUrl, UUID packId) throws IOException {
        long generation = packetGeneration();
        ServerResourcePackLoader loader = mc.getServerResourcePackProvider();
        long connectionEpoch = ReplayResourcePackReleaseBridge.connectionEpoch(loader);
        try (var producer = resourcePackLease.producer(generation)) {
            if (terminal.get()) throw new IOException("Replay sender has ended");
            String reference = replayUrl.substring("replay://".length());
            if (packId == null || !reference.matches("[0-9]+")) {
                throw new IOException("Invalid replay resource-pack id: " + replayUrl);
            }
            int id;
            try { id = Integer.parseInt(reference); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid replay resource-pack index: " + reference, invalid); }
            Map<Integer, String> index = replayFile.getResourcePackIndex();
            if (index == null) throw new IOException("Missing replay resource-pack index");
            String hash = index.get(id);
            if (hash == null || !hash.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("Missing/invalid resource-pack SHA-1 for index " + id);
            }
            // Preserve the archive key's exact case; only the lease normalizes local filenames.
            Path path = resourcePackLease.publish(producer, hash, () -> {
                var source = replayFile.getResourcePack(hash);
                if (source == null || !source.isPresent() || source.get() == null) {
                    throw new IOException("Missing replay resource-pack source: " + hash);
                }
                return source.get();
            });
            ReplayResourcePackReleaseBridge.registerReadyPath(resourcePackLease, path);
            var queued = resourcePackLease.admission(generation);
            try {
                dispatchPacketHandler(() -> {
                    try (queued) {
                        if (!queued.valid() || !callbackCurrent(generation, loader, connectionEpoch)) return;
                        ReplayResourcePackReleaseBridge.submitReady(resourcePackLease, generation, connectionEpoch,
                                loader, packId, path);
                    } catch (IOException failure) {
                        if (!terminal.get()) failure.printStackTrace();
                    }
                });
            } catch (RuntimeException | Error failure) {
                try { queued.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    /** Checked only on actual client execution, never on the constructor-started worker. */
    private boolean callbackCurrent(long generation, ServerResourcePackLoader loader, long connectionEpoch) {
        return resourcePackLease.generation() == generation && sessionCurrent(loader, connectionEpoch);
    }

    private boolean sessionCurrent(ServerResourcePackLoader loader, long connectionEpoch) {
        return mc.isOnThread() && !terminal.get() && replayHandler.ownsFullReplaySender(this)
                && mc.getServerResourcePackProvider() == loader
                && ReplayResourcePackReleaseBridge.connectionEpoch(loader) == connectionEpoch;
    }

    private Runnable guardPacketCallback(Runnable runnable) {
        long generation = packetGeneration();
        ServerResourcePackLoader loader = mc.getServerResourcePackProvider();
        long connectionEpoch = ReplayResourcePackReleaseBridge.connectionEpoch(loader);
        return () -> {
            if (callbackCurrent(generation, loader, connectionEpoch)) runnable.run();
        };
    }

    private Runnable guardControlCallback(Runnable runnable) {
        long intention = controlEpoch.get();
        return guardPacketCallback(() -> {
            if (controlEpoch.get() == intention) runnable.run();
        });
    }

    private void scheduleControlHandler(Runnable runnable) {
        dispatchPacketHandler(guardControlCallback(runnable));
    }

    private void updateSyncClock(int timestamp) {
        realTimeStart = System.currentTimeMillis() - (long) (timestamp / replaySpeed);
        lastTimeStamp = timestamp;
    }

    private void checkInputGenerationLocked() {
        long generation = resourcePackLease.generation();
        // Check EOF and a not-yet-opened boundary too, not only non-null reader/prefetch.
        if (replayInGeneration != generation || (nextPacket != null && nextPacket.generation != generation)) {
            restartPending = true;
        }
    }

    private boolean openReplayInputLocked() throws IOException {
        checkInputGenerationLocked();
        if (terminal.get() || restartPending || startFromBeginning || eofState != EofState.NONE) return false;
        if (replayIn == null) replayIn = replayFile.getPacketData(getPacketTypeRegistry(State.LOGIN));
        return true;
    }

    private void markEofLocked() {
        nextPacket = null;
        synchronized (modeLock) {
            eofState = ReplaySender.super.paused() ? EofState.PAUSED : EofState.NEEDS_PAUSE;
        }
    }

    private boolean tryCloseEofInputLocked() {
        try {
            closeReplayInputLocked();
            return true;
        } catch (IOException | RuntimeException failure) {
            // Keep the exhausted reference/pending bit. Only close may be retried, never read.
            ReplayModReplay.LOGGER.error("Replay EOF reader close failed; retirement remains pending", failure);
            return false;
        }
    }

    private boolean awaitEofRestartLocked(long workerEpoch) throws InterruptedException {
        while (!asyncStopped(workerEpoch)) {
            long completedIntention = -1;
            boolean restart;
            synchronized (modeLock) {
                if (asyncStopped(workerEpoch)) return false;
                // An explicit backward request owns both its desired time and the restart bit.
                if (!startFromBeginning && !restartPending && isHurrying()) {
                    desiredTimeStamp = -1;
                    completedIntention = controlEpoch.get();
                }
                restart = startFromBeginning || restartPending || eofState == EofState.RESUME;
            }
            if (completedIntention != -1) moveCameraAfterHurry(completedIntention);
            if (restart) return true;
            if (eofState == EofState.NEEDS_PAUSE) pauseAtEof(workerEpoch);
            Thread.sleep(10);
            checkInputGenerationLocked();
        }
        return false;
    }

    private void moveCameraAfterHurry(long intention) {
        dispatchPacketHandler(guardPacketCallback(() -> {
            if (controlEpoch.get() == intention) replayHandler.moveCameraToTargetPosition();
        }));
    }

    private void pauseAtEof(long workerEpoch) throws InterruptedException {
        long intention = controlEpoch.get();
        AtomicBoolean ticket = new AtomicBoolean(true);
        try {
            ReplayMod.instance.runSync(guardPacketCallback(() -> {
                synchronized (modeLock) {
                    if (!asyncStopped(workerEpoch) && controlEpoch.get() == intention
                            && eofState == EofState.NEEDS_PAUSE && !startFromBeginning && !restartPending
                            && ticket.compareAndSet(true, false)) {
                        setReplaySpeed(0);
                    }
                }
            }));
        } catch (ExecutionException | TimeoutException failure) {
            ticket.set(false);
            ReplayModReplay.LOGGER.error("Replay EOF pause did not complete; old callback retired", failure);
        } finally {
            ticket.set(false); // runSync does not cancel queued work on timeout/interruption.
        }
    }

    private void pauseAsync(long workerEpoch, long intention) throws Exception {
        ReplayMod.instance.runSync(guardPacketCallback(() -> {
            if (controlEpoch.get() == intention && !asyncStopped(workerEpoch)) setReplaySpeed(0);
        }));
    }

    private boolean restartOwnerCurrent(long workerEpoch, boolean async) {
        return !terminal.get() && asyncEpoch == workerEpoch && asyncMode == async;
    }

    /** Both senders require successful Handler.setup before opening a new LOGIN reader. Caller holds this. */
    private boolean restartInputLocked(long workerEpoch, boolean async) throws IOException, InterruptedException {
        if (!restartOwnerCurrent(workerEpoch, async)) return false;
        if (eofState != EofState.NONE) {
            if (!tryCloseEofInputLocked()) return false;
        } else {
            closeReplayInputLocked();
        }

        final long intention;
        final long generation;
        final boolean resumeOnly;
        synchronized (modeLock) {
            if (!restartOwnerCurrent(workerEpoch, async)) return false;
            checkInputGenerationLocked();
            resumeOnly = !startFromBeginning && !restartPending;
            if (resumeOnly && eofState != EofState.RESUME) return false;
            if (!resumeOnly) restartPending = true;
            startFromBeginning = false;
            nextPacket = null;
            intention = controlEpoch.get();
            generation = resourcePackLease.generation();
        }

        long newGeneration = restartReplay(workerEpoch, async, generation, intention, resumeOnly);
        synchronized (modeLock) {
            if (newGeneration < 0 || terminal.get()) return false;
            // Preserve any new backward request published while the client callback was running.
            hasWorldLoaded = false;
            inBundle = false;
            lastTimeStamp = 0;
            realTimeStart = System.currentTimeMillis();
            registry = getPacketTypeRegistry(State.LOGIN);
            replayInGeneration = newGeneration;
            restartPending = false;
            eofState = EofState.NONE;
        }
        return true;
    }

    private long restartReplay(long workerEpoch, boolean async, long generation, long intention, boolean resumeOnly)
            throws InterruptedException {
        ServerResourcePackLoader loader = mc.getServerResourcePackProvider();
        long connectionEpoch = ReplayResourcePackReleaseBridge.connectionEpoch(loader);
        AtomicBoolean ticket = new AtomicBoolean(true);
        AtomicLong restartedGeneration = new AtomicLong(-1);
        try {
            ReplayMod.instance.runSync(() -> {
                Channel previous;
                synchronized (modeLock) {
                    if (!restartOwnerCurrent(workerEpoch, async) || controlEpoch.get() != intention
                            || !callbackCurrent(generation, loader, connectionEpoch)
                            || (resumeOnly && eofState != EofState.RESUME)
                            || !ticket.compareAndSet(true, false)) return;
                    restartPending = true;
                    previous = channel;
                    advancePacketGeneration();
                }
                // No sender lock acquired here, and don't hold modeLock across disconnect/setup.
                replayHandler.restartedReplay();
                synchronized (modeLock) {
                    if (!terminal.get() && replayHandler.ownsFullReplaySender(this)
                            && channel != null && channel != previous && channel.isActive()
                            && channel.pipeline().get(ReplayHandler.PACKET_HANDLER_NAME) == this) {
                        restartedGeneration.set(resourcePackLease.generation());
                    }
                }
            });
            return restartedGeneration.get();
        } catch (ExecutionException | TimeoutException failure) {
            ticket.set(false);
            ReplayModReplay.LOGGER.error("Replay restart did not complete; old callback retired", failure);
            return -1;
        } finally {
            ticket.set(false); // A queued callback has no authority after this attempt exits.
        }
    }

    /**
     * Runs the given runnable on the main thread as if it was a packet handler.
     * Note that the packet handler queue has different behavior than the standard ReplayMod queue.
     */
    private void schedulePacketHandler(Runnable runnable) {
        dispatchPacketHandler(guardPacketCallback(runnable));
    }

    private void dispatchPacketHandler(Runnable runnable) {
        if (mc.isOnThread()) {
            runnable.run();
        } else {
            //#if MC>=11400
            mc.execute(runnable);
            //#else
            //$$ mc.addScheduledTask(runnable);
            //#endif
        }
    }

    protected void processPacketSync(Packet p) {
        //#if MC>=10904
        if (p instanceof UnloadChunkS2CPacket) {
            UnloadChunkS2CPacket packet = (UnloadChunkS2CPacket) p;
            int x = packet.pos().x;
            int z = packet.pos().z;
        //#else
        //$$ if (p instanceof S21PacketChunkData && ((S21PacketChunkData) p).getExtractedSize() == 0) {
        //$$     S21PacketChunkData packet = (S21PacketChunkData) p;
        //$$     int x = packet.getChunkX();
        //$$     int z = packet.getChunkZ();
        //#endif
            // If the chunk is getting unloaded, we will have to forcefully update the position of all entities
            // within. Otherwise, if there wasn't a game tick recently, there may be entities that have moved
            // out of the chunk by now but are still registered in it. If we do not update those, they will get
            // unloaded even though they shouldn't.
            // Note: This is only half of the truth. Entities may be removed by chunk-unloading, see else-case below.
            // To make things worse, it seems like players were never supposed to be unloaded this way because
            // they will remain glitched in the World#playerEntities list.
            // 1.14+: The update issue remains but only for non-players and the unloading list bug appears to have been
            //        fixed (chunk unloading no longer removes the entities).
            // Get the chunk that will be unloaded
            //#if MC>=11400
            ClientWorld world = mc.world;
            ChunkManager chunkProvider = world.getChunkManager();
            WorldChunk chunk = chunkProvider.getWorldChunk(x, z
                    //#if MC<11500
                    //$$ , false
                    //#endif
            );
            if (chunk != null) {
            //#else
            //$$ World world = mc.world;
            //$$ IChunkProvider chunkProvider = world.getChunkProvider();
            //$$ Chunk chunk = chunkProvider.provideChunk(x, z);
            //$$ if (!chunk.isEmpty()) {
            //#endif
                List<Entity> entitiesInChunk = new ArrayList<>();
                // Gather all entities in that chunk
                //#if MC>=11700
                for (Entity entity : mc.world.getEntities()) {
                    if (entity.getChunkPos().equals(chunk.getPos())) {
                        entitiesInChunk.add(entity);
                    }
                }
                //#else
                //$$ for (Collection<Entity> entityList : chunk.getEntitySectionArray()) {
                //$$     entitiesInChunk.addAll(entityList);
                //$$ }
                //#endif
                for (Entity entity : entitiesInChunk) {
                    // Skip interpolation of position updates coming from server
                    // (See: newX in EntityLivingBase or otherPlayerMPX in EntityOtherPlayerMP)
                    forcePositionForVehicleAndSelf(entity);

                    // Check whether the entity has left the chunk
                    //#if MC>=11700
                    // This is now handled automatically in Entity.setPos (called from tick())
                    //#elseif MC>=11404
                    //$$ int chunkX = MathHelper.floor(entity.getX() / 16);
                    //$$ int chunkY = MathHelper.floor(entity.getY() / 16);
                    //$$ int chunkZ = MathHelper.floor(entity.getZ() / 16);
                    //$$ if (entity.chunkX != chunkX || entity.chunkY != chunkY || entity.chunkZ != chunkZ) {
                    //$$     if (entity.updateNeeded) {
                    //$$         // Entity has left the chunk
                    //$$         chunk.remove(entity, entity.chunkY);
                    //$$     }
                    //$$     WorldChunk newChunk = chunkProvider.getWorldChunk(chunkX, chunkZ
                                //#if MC<11500
                                //$$ , false
                                //#endif
                    //$$     );
                    //$$     if (newChunk != null) {
                    //$$         newChunk.addEntity(entity);
                    //$$     } else {
                    //$$         // Entity has left all loaded chunks
                    //$$         entity.updateNeeded = false;
                    //$$     }
                    //$$ }
                    //#else
                    //$$ int chunkX = MathHelper.floor(entity.posX / 16);
                    //$$ int chunkZ = MathHelper.floor(entity.posZ / 16);
                    //$$ if (entity.chunkCoordX != chunkX || entity.chunkCoordZ != chunkZ) {
                    //$$     // Entity has left the chunk
                    //$$     chunk.removeEntityAtIndex(entity, entity.chunkCoordY);
                        //#if MC>=10904
                        //$$ Chunk newChunk = chunkProvider.getLoadedChunk(chunkX, chunkZ);
                        //#else
                        //$$ Chunk newChunk = chunkProvider.chunkExists(chunkX, chunkZ)
                        //$$         ? chunkProvider.provideChunk(chunkX, chunkZ) : null;
                        //#endif
                    //$$     if (newChunk != null) {
                    //$$         newChunk.addEntity(entity);
                    //$$     } else {
                    //$$         // Entity has left all loaded chunks
                    //$$         entity.addedToChunk = false;
                    //$$     }
                    //$$ } else {
                    //$$     // When entities remain in a chunk that's to be unloaded, they'll only be added to a unload
                    //$$     // queue and remain loaded as before until the next tick (which during jumping is way off).
                    //$$     // So, if they are re-spawned with the same entity id, MC actually cleans up the old entity and
                    //$$     // then adds the new one but leaves the unload queue as is.
                    //$$     // Finally, on the next tick the legitimate entity will be unloaded because it's part of the
                    //$$     // unload queue (entities .equals based purely on their id). However, the old entity object
                    //$$     // is used to determine the chunk the entity is removed from and in this case that'll allow the
                    //$$     // legitimate entity to remain registered in a loaded chunk, causing them to still be rendered.
                    //$$     //
                    //$$     // The usual removal-due-to-chunk-unload process will, without touching the entityList, call
                    //$$     // onEntityRemoved. In that method WorldClient checks to see whether the entity is still in the
                    //$$     // entityList (which it is) and then adds it to the entitySpawnQueue.
                    //$$     // As the final result the entity will remain loaded.
                    //$$     // To get the same result without ticking, we just remove the entity from the to-be-unloaded
                    //$$     // chunk but keep it loaded otherwise. They won't be rendered because they're not part of any
                    //$$     // chunk and will be removed properly if the server decides to re-spawn the entity.
                    //$$     chunk.removeEntityAtIndex(entity, entity.chunkCoordY);
                    //$$     entity.addedToChunk = false;
                    //$$ }
                    //#endif
                }
            }
        }
    }

    private void forcePositionForVehicleAndSelf(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            forcePositionForVehicleAndSelf(vehicle);
        }

        // Skip interpolation of position updates coming from server
        // (See: newX in EntityLivingBase or otherPlayerMPX in EntityOtherPlayerMP)
        int ticks = 0;
        Vec3d prevPos;
        do {
            prevPos = entity.getPos();
            if (vehicle != null) {
                entity.tickRiding();
            } else {
                entity.tick();
            }
        } while (prevPos.squaredDistanceTo(entity.getPos()) > 0.0001 && ticks++ < 100);
    }

    private static final class PacketData {
        private static final com.github.steveice10.netty.buffer.ByteBuf byteBuf = com.github.steveice10.netty.buffer.Unpooled.buffer();
        private static final NetOutput netOutput = new ByteBufNetOutput(byteBuf);

        private final int timestamp;
        private final byte[] bytes;
        private final PacketType type;

        private final long generation;

        PacketData(ReplayInputStream in, long generation) throws IOException {
            this.generation = generation;
            if (ReplayMod.isMinimalMode()) {
                // Minimal mode, we can only read our exact protocol version and cannot use ReplayStudio
                timestamp = readInt(in);
                int length = readInt(in);
                if (timestamp == -1 || length == -1) {
                    throw new EOFException();
                }
                bytes = new byte[length];
                IOUtils.readFully(in, bytes);
                type = PacketType.UnknownLogin;
            } else {
                com.replaymod.replaystudio.PacketData data = in.readPacket();
                if (data == null) {
                    throw new EOFException();
                }
                timestamp = (int) data.getTime();
                com.replaymod.replaystudio.protocol.Packet packet = data.getPacket();
                type = packet.getType();
                // Workaround for ReplayMod 2.7.16-17 saving the LoginSuccess packet with an incorrect packet id
                // A fake one will have been sythesized by ReplayStudo, so we can simply drop the broken one.
                if (packet.getId() == -1) {
                    bytes = new byte[0];
                    return;
                }
                // We need to re-encode ReplayStudio packets, so we can later decode them as NMS packets
                // The main reason we aren't reading them as NMS packets is that we want ReplayStudio to be able
                // to apply ViaVersion (and potentially other magic) to it.
                synchronized (byteBuf) {
                    byteBuf.markReaderIndex(); // Mark the current reader and writer index (should be at start)
                    byteBuf.markWriterIndex();

                    netOutput.writeVarInt(packet.getId());
                    int idSize = byteBuf.readableBytes();
                    int contentSize = packet.getBuf().readableBytes();
                    bytes = new byte[idSize + contentSize]; // Create bytes array of sufficient size
                    byteBuf.readBytes(bytes, 0, idSize);
                    packet.getBuf().readBytes(bytes, idSize, contentSize);

                    byteBuf.resetReaderIndex(); // Reset reader & writer index for next use
                    byteBuf.resetWriterIndex();
                }
                packet.getBuf().release();
            }
        }
    }
}
