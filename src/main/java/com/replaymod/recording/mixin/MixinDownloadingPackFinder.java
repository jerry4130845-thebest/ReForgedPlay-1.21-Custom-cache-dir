package com.replaymod.recording.mixin;

import com.replaymod.recording.packet.ResourcePackRecorder;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.util.Downloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Mixin(ServerResourcePackManager.class)
public abstract class MixinDownloadingPackFinder {
    // PackEntry has Object identity (verified in the named 1.21.1 JAR). Weak keys do not
    // keep discarded-before-download packs or an old recorder alive for the manager's lifetime.
    @Unique private final Map<Object, org.apache.commons.lang3.tuple.Pair<UUID, ResourcePackRecorder.DownloadRequest>>
            replaymod$owners = new java.util.WeakHashMap<>();

    @Inject(method = "onAdd", at = @At("HEAD"))
    private void captureOwner(UUID uuid, @Coerce Object pack, CallbackInfo ci) {
        ResourcePackRecorder.DownloadRequest request = ResourcePackRecorder.claimDownloadRequest(uuid);
        if (request == null) return; // no proven admitted request: never infer ownership later
        synchronized (replaymod$owners) { replaymod$owners.put(pack, org.apache.commons.lang3.tuple.Pair.of(uuid, request)); }
    }

    @Inject(method = "onDownload", at = @At("HEAD"))
    private void recordDownloadedPack(@Coerce Object packs, Downloader.DownloadResult result, CallbackInfo ci) {
        if (!(packs instanceof java.util.Collection<?> batch)) return;
        for (Object pack : batch) {
            org.apache.commons.lang3.tuple.Pair<UUID, ResourcePackRecorder.DownloadRequest> owner;
            synchronized (replaymod$owners) { owner = replaymod$owners.remove(pack); }
            if (owner == null) continue; // unknown/old callback is safely ignored
            Path file = result.downloaded().get(owner.getLeft());
            if (file != null) owner.getRight().record(file);
        }
    }
}
