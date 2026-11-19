package com.replaymod.recording.packet;

import com.google.common.hash.Hashing;
import com.replaymod.replaystudio.replay.ReplayFile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//#if MC>=12003
//$$ import java.util.UUID;
//#endif

//#if MC>=11900
import java.net.MalformedURLException;
import java.net.URL;
//#endif

//#if MC>=11400
import net.minecraft.text.TranslatableTextContent;
//#else
//$$ import net.minecraft.client.gui.GuiYesNoCallback;
//$$ import net.minecraft.client.resources.I18n;
//#endif

//#if MC>=10800
import de.johni0702.minecraft.gui.utils.Consumer;
//#else
//$$ import net.minecraft.client.gui.GuiScreenWorking;
//$$ import net.minecraft.util.HttpUtil;
//#endif

//#if MC>=10800

//#endif

//#if MC>=10800
//#if MC>=11400
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
//#else
//$$ import com.google.common.util.concurrent.ListenableFuture;
//#endif
import net.minecraft.network.ClientConnection;
//#else
//$$ import com.replaymod.core.mixin.ResourcePackRepositoryAccessor;
//$$ import net.minecraft.client.multiplayer.ServerData.ServerResourceMode;
//$$ import net.minecraft.client.multiplayer.ServerList;
//$$ import net.minecraft.client.resources.FileResourcePack;
//$$ import net.minecraft.network.play.server.S3FPacketCustomPayload;
//$$ import org.apache.commons.io.Charsets;
//#endif

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.replaymod.core.versions.MCVer.*;

/**
 * Records resource packs and handles incoming resource pack packets during recording.
 */
public class ResourcePackRecorder {
    private static final Logger logger = LogManager.getLogger();
    private static final MinecraftClient mc = getMinecraft();

    private final ReplayFile replayFile;

    private int nextRequestId;

    public ResourcePackRecorder(ReplayFile replayFile) {
        this.replayFile = replayFile;
    }

    private boolean sealed;
    private Throwable firstFailure;

    /** Same monitor covers admission, reading, hash, blob close and last index publication. */
    public synchronized void recordResourcePack(Path file, int requestId) {
        if (sealed) return; // reject BEFORE touching an old download's path/volume
        try {
            byte[] bytes = Files.readAllBytes(file);
            String hash = Hashing.sha1().hashBytes(bytes).toString();
            synchronized (replayFile) {
                Map<Integer, String> existing = replayFile.getResourcePackIndex();
                Map<Integer, String> index = existing == null ? new HashMap<>() : new HashMap<>(existing);
                if (!index.containsValue(hash)) {
                    try (OutputStream out = replayFile.writeResourcePack(hash)) { out.write(bytes); }
                }
                // A failed blob/close never publishes a reference to a missing blob.
                index.put(requestId, hash);
                replayFile.writeResourcePackIndex(index);
            }
        } catch (Throwable failure) {
            if (firstFailure == null) firstFailure = failure;
            logger.warn("Failed to save resource pack.", failure);
        }
    }

    /** Called by the saving worker without holding replayFile. Waits for the entire admitted callback. */
    public synchronized void seal() throws IOException {
        sealed = true;
        mcIdToReplayId.clear();
        if (firstFailure != null) throw new IOException("Resource-pack recording failed", firstFailure);
    }

    //#if MC>=12003
    private final Map<UUID, java.util.ArrayDeque<Integer>> mcIdToReplayId = new HashMap<>();
    // A consent screen may delay onAdd until after another recording starts. NEVER use the
    // current listener there. Values do not strongly reference the UUID key or the recorder.
    // MC's verified caller chain carries the packet's UUID through consent and into PackEntry.
    private static final Map<UUID, java.util.List<DownloadRequest>> pendingDownloads = new java.util.WeakHashMap<>();

    public static final class DownloadRequest {
        private final java.lang.ref.WeakReference<ResourcePackRecorder> recorder;
        private final int request;
        private DownloadRequest(ResourcePackRecorder recorder, int request) {
            this.recorder = new java.lang.ref.WeakReference<>(recorder); this.request = request;
        }
        public void record(Path file) {
            ResourcePackRecorder owner = recorder.get();
            if (owner != null) owner.recordResourcePack(file, request);
        }
    }

    public synchronized ResourcePackSendS2CPacket handleResourcePack(ClientConnection netManager, ResourcePackSendS2CPacket packet) {
        if (sealed) return packet;
        final int requestId = nextRequestId++;
        mcIdToReplayId.computeIfAbsent(packet.id(), ignored -> new java.util.ArrayDeque<>()).addLast(requestId);
        synchronized (pendingDownloads) {
            pendingDownloads.computeIfAbsent(packet.id(), ignored -> new java.util.ArrayList<>())
                    .add(new DownloadRequest(this, requestId));
        }
        return new ResourcePackSendS2CPacket(packet.id(), "replay://" + requestId, "", packet.required(), packet.prompt());
    }

    /** One proven request is bound once to PackEntry identity. Ambiguous delayed consent is rejected. */
    public static DownloadRequest claimDownloadRequest(UUID uuid) {
        DownloadRequest request;
        synchronized (pendingDownloads) {
            java.util.List<DownloadRequest> requests = pendingDownloads.get(uuid);
            if (requests == null) return null;
            if (requests.size() != 1) {
                // Retain this weak-key tombstone: guessing FIFO could attach old consent to a new task.
                logger.warn("Ambiguous reused resource-pack UUID before onAdd; not recording this download: " + uuid);
                return null;
            }
            request = requests.getFirst();
            pendingDownloads.remove(uuid);
        } // Never take a recorder monitor while holding the pending-map monitor.
        ResourcePackRecorder owner = request.recorder.get();
        if (owner == null) return null;
        synchronized (owner) {
            if (owner.sealed) return null;
            java.util.ArrayDeque<Integer> ids = owner.mcIdToReplayId.get(uuid);
            if (ids == null || !ids.removeFirstOccurrence(request.request)) return null;
            if (ids.isEmpty()) owner.mcIdToReplayId.remove(uuid);
        }
        return request;
    }

    public synchronized void recordResourcePack(Path file, UUID uuid) {
        if (sealed) return;
        java.util.ArrayDeque<Integer> ids = mcIdToReplayId.get(uuid);
        if (ids == null || ids.size() != 1) {
            logger.warn("Got resource pack download without unique request ownership: " + uuid);
            return;
        }
        int id = ids.removeFirst(); mcIdToReplayId.remove(uuid);
        recordResourcePack(file, id);
    }
    //#elseif MC>=10800
    //$$ public ResourcePackStatusC2SPacket makeStatusPacket(String hash, Status action) {
    //$$     //#if MC>=11002
    //$$     return new ResourcePackStatusC2SPacket(action);
    //$$     //#else
    //$$     //$$ return new CPacketResourcePackStatus(hash, action);
    //$$     //#endif
    //$$ }


    //$$ public synchronized ResourcePackSendS2CPacket handleResourcePack(ClientConnection netManager, ResourcePackSendS2CPacket packet) {
    //$$     final int requestId = nextRequestId++;
    //$$     final String url = packet.getURL();
    //$$     final String hash = packet.getSHA1();

    //$$     if (url.startsWith("level://")) {
    //$$         String levelName = url.substring("level://".length());
    //$$         File savesDir = new File(mc.runDirectory, "saves");
    //$$        final File levelDir = new File(savesDir, levelName);
//$$
    //$$         if (levelDir.isFile()) {
    //$$             netManager.send(makeStatusPacket(hash, Status.ACCEPTED));
    //$$             addCallback(setServerResourcePack(levelDir), result -> {
    //$$                recordResourcePack(levelDir.toPath(), requestId);
    //$$                 netManager.send(makeStatusPacket(hash, Status.SUCCESSFULLY_LOADED));
    //$$             }, throwable -> {
    //$$                 netManager.send(makeStatusPacket(hash, Status.FAILED_DOWNLOAD));
    //$$             });
    //$$         } else {
    //$$             netManager.send(makeStatusPacket(hash, Status.FAILED_DOWNLOAD));
    //$$         }
    //$$     } else {
    //$$         final ServerInfo serverData = mc.getCurrentServerEntry();
    //$$         if (serverData != null && serverData.getResourcePackPolicy() == ServerInfo.ResourcePackPolicy.ENABLED) {
    //$$             netManager.send(makeStatusPacket(hash, Status.ACCEPTED));
    //$$             downloadResourcePackFuture(netManager, requestId, url, hash);
    //$$         } else if (serverData != null && serverData.getResourcePackPolicy() != ServerInfo.ResourcePackPolicy.PROMPT) {
    //$$             netManager.send(makeStatusPacket(hash, Status.DECLINED));
    //$$         } else {
    //$$             // Lambdas MUST NOT be used with methods that need re-obfuscation in FG prior to 2.2 (will result in AbstractMethodError)
    //$$             //#if MC>=11400
    //$$             mc.execute(() -> mc.setScreen(new ConfirmScreen(result -> {
    //$$             //#else
    //$$            //$$ //noinspection Convert2Lambda
    //$$             //$$ mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiYesNo(new GuiYesNoCallback() {
    //$$             //$$     @Override
    //$$             //$$     public void confirmClicked(boolean result, int id) {
    //$$             //#endif
    //$$                      if (serverData != null) {
    //$$                          serverData.setResourcePackPolicy(result ? ServerInfo.ResourcePackPolicy.ENABLED : ServerInfo.ResourcePackPolicy.DISABLED);
    //$$                      }
    //$$                      if (result) {
    //$$                          netManager.send(makeStatusPacket(hash, Status.ACCEPTED));
    //$$                          downloadResourcePackFuture(netManager, requestId, url, hash);
    //$$                      } else {
    //$$                         netManager.send(makeStatusPacket(hash, Status.DECLINED));
    //$$                      }
//$$
    //$$                     ServerList.updateServerListEntry(serverData);
    //$$                     mc.setScreen(null);
    //$$                 }
    //$$             //#if MC>=11400
    //$$             , net.minecraft.text.Text.translatable("multiplayer.texturePrompt.line1"), net.minecraft.text.Text.translatable("multiplayer.texturePrompt.line2"))));
    //$$             //#else
    //$$             //$$ }, I18n.format("multiplayer.texturePrompt.line1"), I18n.format("multiplayer.texturePrompt.line2"), 0)));
    //$$             //#endif
    //$$         }
    //$$     }
    //$$ //$$
    //$$     return new ResourcePackSendS2CPacket(
    //$$             "replay://" + requestId, ""
    //$$             //#if MC>=11700
    //$$             , packet.isRequired(), packet.getPrompt()
    //$$             //#endif
    //$$     );
    //$$ }
    //$$ //$$
    //$$ private void downloadResourcePackFuture(ClientConnection connection, int requestId, String url, final String hash) {
    //$$     addCallback(downloadResourcePack(requestId, url, hash),
    //$$             result -> connection.send(makeStatusPacket(hash, Status.SUCCESSFULLY_LOADED)),
    //$$             throwable -> connection.send(makeStatusPacket(hash, Status.FAILED_DOWNLOAD)));
    //$$ }
    //$$ //$$
    //$$ private
    //$$ //#if MC>=11400
    //$$ CompletableFuture<?>
    //$$ //#else
    //$$ //$$ ListenableFuture<?>
    //$$ //#endif
    //$$ downloadResourcePack(final int requestId, String url, String hash) {
    //$$     ServerResourcePackProvider packFinder = mc.getServerResourcePackProvider();
    //$$     ((IDownloadingPackFinder) packFinder).setRequestCallback(file -> recordResourcePack(file.toPath(), requestId));
    //$$     //#if MC>=11900
    //$$     try {
    //$$         URL theUrl = new URL(url);
    //$$         String protocol = theUrl.getProtocol();
    //$$         if (!"http".equals(protocol) && !"https".equals(protocol)) {
    //$$             throw new MalformedURLException("Unsupported protocol.");
    //$$         }
    //$$         return packFinder.download(theUrl, hash, true);
    //$$     } catch (MalformedURLException e) {
    //$$         return CompletableFuture.failedFuture(e);
    //$$     }
    //$$     //#elseif MC>=11700
    //$$     //$$ return packFinder.download(url, hash, true);
    //$$     //#else
    //$$     //$$ return packFinder.download(url, hash);
    //$$     //#endif
    //$$ }

    //$$  public interface IDownloadingPackFinder {
    //$$     void setRequestCallback(Consumer<File> callback);
    //$$ }

    //$$ private record ResourcePackSendS2CPacket(String s, String s1, Object required, Object prompt) {
    //$$ }
    //#else
    //$$ public synchronized S3FPacketCustomPayload handleResourcePack(S3FPacketCustomPayload packet) {
    //$$     final int requestId = nextRequestId++;
    //$$     final String url = new String(packet.func_149168_d(), Charsets.UTF_8);
    //$$
    //$$     ServerData serverData = mc.getCurrentServerData();
    //$$     ServerResourceMode resourceMode = serverData == null ? ServerResourceMode.PROMPT : serverData.getResourceMode();
    //$$     if (resourceMode == ServerResourceMode.ENABLED) {
    //$$         downloadResourcePack(requestId, url);
    //$$         mc.getResourcePackRepository().obtainResourcePack(url);
    //$$     } else if (resourceMode == ServerResourceMode.PROMPT) {
    //$$         // Lambdas MUST NOT be used with methods that need re-obfuscation in FG prior to 2.2 (will result in AbstractMethodError)
    //$$         //noinspection Convert2Lambda
    //$$         mc.displayGuiScreen(new GuiYesNo(new GuiYesNoCallback() {
    //$$             @Override
    //$$             public void confirmClicked(boolean result, int id) {
    //$$                 if (serverData != null) {
    //$$                     serverData.setResourceMode(ServerResourceMode.ENABLED);
    //$$                     ServerList.func_147414_b(serverData);
    //$$                 }
    //$$
    //$$                 mc.displayGuiScreen(null);
    //$$
    //$$                 if (result) {
    //$$                     downloadResourcePack(requestId, url);
    //$$                 }
    //$$             }
    //$$         }, I18n.format("multiplayer.texturePrompt.line1"), I18n.format("multiplayer.texturePrompt.line2"), 0));
    //$$     }
    //$$
    //$$     return new S3FPacketCustomPayload(packet.func_149169_c(), ("replay://" + requestId).getBytes(Charsets.UTF_8));
    //$$ }
    //$$
    //$$ private void downloadResourcePack(final int requestId, String url) {
    //$$     final ResourcePackRepository repo = mc.getResourcePackRepository();
    //$$     final ResourcePackRepositoryAccessor acc = (ResourcePackRepositoryAccessor) repo;
    //$$
    //$$     String fileName = url.substring(url.lastIndexOf("/") + 1);
    //$$
    //$$     if (fileName.contains("?")) {
    //$$         fileName = fileName.substring(0, fileName.indexOf("?"));
    //$$     }
    //$$
    //$$     if (!fileName.endsWith(".zip")) {
    //$$         return;
    //$$     }
    //$$
    //$$     fileName = fileName.replaceAll("\\W", "");
    //$$
    //$$     File file = new File(acc.getCacheDir(), fileName);
    //$$
    //$$     HashMap<String, String> hashmap = new HashMap<>();
    //$$     hashmap.put("X-Minecraft-Username", mc.getSession().getUsername());
    //$$     hashmap.put("X-Minecraft-UUID", mc.getSession().getPlayerID());
    //$$     hashmap.put("X-Minecraft-Version", "1.7.10");
    //$$
    //$$     GuiScreenWorking guiScreen = new GuiScreenWorking();
    //$$     Minecraft.getMinecraft().displayGuiScreen(guiScreen);
    //$$     repo.func_148529_f();
    //$$     acc.setActive(true);
    //$$     // Lambdas MUST NOT be used with methods that need re-obfuscation in FG prior to 2.2 (will result in AbstractMethodError)
    //$$     //noinspection Convert2Lambda
    //$$     HttpUtil.downloadResourcePack(file, url, new HttpUtil.DownloadListener() {
    //$$         public void onDownloadComplete(File file) {
    //$$             if (acc.isActive()) {
    //$$                 acc.setActive(false);
    //$$                 acc.setPack(new FileResourcePack(file));
    //$$                 Minecraft.getMinecraft().scheduleResourcesRefresh();
    //$$                 recordResourcePack(file.toPath(), requestId);
    //$$             }
    //$$         }
    //$$     }, hashmap, 50*1024*1024, guiScreen, Minecraft.getMinecraft().getProxy());
    //$$ }
    //#endif

}
