package com.replaymod.core.mixin;

import com.replaymod.core.files.ReplayResourcePackReleaseBridge;
import net.minecraft.client.resource.server.ReloadScheduler;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ZipResourcePack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Loader ownership/provider evidence only. No global unload or release inference. */
@Mixin(ServerResourcePackLoader.class)
public abstract class Mixin_ServerResourcePackLoaderLease {
    @Shadow @org.spongepowered.asm.mixin.Final private static ResourcePackProvider NOOP_PROVIDER;
    @Shadow private ResourcePackProvider packProvider;
    @Shadow private ReloadScheduler.ReloadContext reloadContext;

    @Redirect(method = "toProfiles(Ljava/util/List;)Ljava/util/List;",
            at = @At(value = "NEW", target = "(Ljava/nio/file/Path;)Lnet/minecraft/resource/ZipResourcePack$ZipBackedFactory;"),
            require = 1, expect = 1, allow = 1)
    private ZipResourcePack.ZipBackedFactory replaymod$factory(Path path) {
        return ReplayResourcePackReleaseBridge.newZipFactory(path);
    }

    @Inject(method = "toProfiles(Ljava/util/List;)Ljava/util/List;", at = @At("RETURN"),
            require = 2, expect = 2, allow = 2)
    private void replaymod$profiles(List<ReloadScheduler.PackInfo> packs,
                                   CallbackInfoReturnable<List<ResourcePackProfile>> ci) {
        ReplayResourcePackReleaseBridge.profilesCreated(this, packs, ci.getReturnValue());
    }

    @Inject(method = "getPackProvider(Ljava/util/List;)Lnet/minecraft/resource/ResourcePackProvider;",
            at = @At("RETURN"), require = 2, expect = 2, allow = 2)
    private static void replaymod$provider(List<ResourcePackProfile> profiles,
                                         CallbackInfoReturnable<ResourcePackProvider> ci) {
        ReplayResourcePackReleaseBridge.providerCreated(ci.getReturnValue(), profiles);
    }

    @Redirect(method = {
            "reload(Lnet/minecraft/client/resource/server/ReloadScheduler$ReloadContext;)V",
            "onReloadFailure()V", "onForcedReloadFailure()V" },
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/resource/server/ServerResourcePackLoader;packProvider:Lnet/minecraft/resource/ResourcePackProvider;", opcode = Opcodes.PUTFIELD),
            require = 3, expect = 3, allow = 3)
    private void replaymod$installProvider(ServerResourcePackLoader loader, ResourcePackProvider provider) {
        this.packProvider = provider;
        ReplayResourcePackReleaseBridge.providerInstalled(loader, this.reloadContext, provider, provider == NOOP_PROVIDER);
    }

    @Redirect(method = "addResourcePack(Ljava/util/UUID;Ljava/nio/file/Path;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resource/server/ServerResourcePackManager;addResourcePack(Ljava/util/UUID;Ljava/nio/file/Path;)V"),
            require = 1, expect = 1, allow = 1)
    private void replaymod$addPath(ServerResourcePackManager manager, UUID id, Path path) throws Throwable {
        ReplayResourcePackReleaseBridge.Ticket ticket = ReplayResourcePackReleaseBridge.beforePathAdd(this, id, path);
        Throwable failure = null;
        try {
            manager.addResourcePack(id, path);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            ReplayResourcePackReleaseBridge.addFinished(ticket, failure);
        }
    }
    @Redirect(method = "addResourcePack(Ljava/util/UUID;Ljava/net/URL;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resource/server/ServerResourcePackManager;addResourcePack(Ljava/util/UUID;Ljava/net/URL;Lcom/google/common/hash/HashCode;)V"),
            require = 1, expect = 1, allow = 1)
    private void replaymod$addUrl(ServerResourcePackManager manager, UUID id, URL url,
                                  com.google.common.hash.HashCode hash) throws Throwable {
        ReplayResourcePackReleaseBridge.Ticket ticket = ReplayResourcePackReleaseBridge.beforeUrlAdd(this, id);
        Throwable failure = null;
        try {
            manager.addResourcePack(id, url, hash);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            ReplayResourcePackReleaseBridge.addFinished(ticket, failure);
        }
    }
    @Inject(method = "remove(Ljava/util/UUID;)V", at = @At("RETURN"),
            require = 1, expect = 1, allow = 1)
    private void replaymod$remove(UUID id, CallbackInfo ci) {
        ReplayResourcePackReleaseBridge.removed(this, id);
    }
    @Inject(method = { "removeAll()V", "clear()V" }, at = @At("RETURN"),
            require = 2, expect = 2, allow = 2)
    private void replaymod$clear(CallbackInfo ci) {
        ReplayResourcePackReleaseBridge.cleared(this);
    }
    @Inject(method = "init(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/client/resource/server/ServerResourcePackManager$AcceptanceStatus;)V",
            at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void replaymod$connection(ClientConnection connection, ServerResourcePackManager.AcceptanceStatus status,
                                     CallbackInfo ci) {
        ReplayResourcePackReleaseBridge.connectionChanged(this, connection);
    }
}
