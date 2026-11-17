package com.replaymod.core.mixin;

import com.replaymod.core.files.ReplayResourcePackReleaseBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceReload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Actual cycle evidence, never Loader.currentContext or a thenRun release shortcut. */
@Mixin(MinecraftClient.class)
public abstract class Mixin_MinecraftResourceReloadLease {
    @Inject(method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/resource/ReloadableResourceManagerImpl;reload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/resource/ResourceReload;"),
            locals = LocalCapture.CAPTURE_FAILHARD, require = 1, expect = 1, allow = 1)
    private void replaymod$cycle(boolean force, @Coerce Object context,
                                 CallbackInfoReturnable<CompletableFuture<Void>> ci,
                                 CompletableFuture<Void> future, List<ResourcePack> packs) {
        ReplayResourcePackReleaseBridge.cycleStarting(this, future, packs);
    }

    @Redirect(method = "reloadResources(ZLnet/minecraft/client/MinecraftClient$LoadingContext;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/resource/ReloadableResourceManagerImpl;reload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/resource/ResourceReload;"),
            require = 1, expect = 1, allow = 1)
    private ResourceReload replaymod$install(ReloadableResourceManagerImpl manager, Executor prepare,
                                             Executor apply, CompletableFuture<net.minecraft.util.Unit> initial,
                                             List<ResourcePack> packs) throws Throwable {
        try {
            ResourceReload reload = manager.reload(prepare, apply, initial, packs);
            ReplayResourcePackReleaseBridge.cycleInstalled(this, packs);
            return reload;
        } catch (Throwable failure) {
            ReplayResourcePackReleaseBridge.cycleInstallFailed(this, packs, failure);
            throw failure;
        }
    }

    @Inject(method = "lambda$reloadResourcePacks$21(ZLnet/minecraft/client/MinecraftClient$LoadingContext;Ljava/util/concurrent/CompletableFuture;Ljava/util/Optional;)V",
            at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void replaymod$failed(boolean force, @Coerce Object context, CompletableFuture<Void> future,
                                  Optional<Throwable> failure, CallbackInfo ci) {
        failure.ifPresent(cause -> ReplayResourcePackReleaseBridge.cycleFailed(this, future, cause));
    }

    @Inject(method = "lambda$reloadResourcePacks$20(Ljava/util/concurrent/CompletableFuture;Lnet/minecraft/client/MinecraftClient$LoadingContext;)V",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private void replaymod$succeeded(CompletableFuture<Void> future, @Coerce Object context, CallbackInfo ci) {
        ReplayResourcePackReleaseBridge.cycleSucceeded(this, future);
    }
}
