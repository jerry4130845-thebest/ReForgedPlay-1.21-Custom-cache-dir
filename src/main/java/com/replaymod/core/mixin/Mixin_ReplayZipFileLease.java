package com.replaymod.core.mixin;

import com.replaymod.core.files.ReplayResourcePackReleaseBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.zip.ZipFile;

/** Owned wrappers only. Foreign open/close stays vanilla; never infer release from close RETURN. */
@Mixin(targets = "net.minecraft.resource.ZipResourcePack$ZipFileWrapper")
public abstract class Mixin_ReplayZipFileLease {
    @Unique private ReplayResourcePackReleaseBridge.OwnedZip replaymod$ownedZip;

    @Inject(method = "<init>(Ljava/io/File;)V", at = @At("RETURN"),
            require = 1, expect = 1, allow = 1)
    private void replaymod$bind(File file, CallbackInfo ci) {
        replaymod$ownedZip = ReplayResourcePackReleaseBridge.bindZip(this, file);
    }

    @Inject(method = "open()Ljava/util/zip/ZipFile;", at = @At("HEAD"), cancellable = true,
            require = 1, expect = 1, allow = 1)
    private void replaymod$open(CallbackInfoReturnable<ZipFile> ci) {
        if (replaymod$ownedZip != null) {
            // C: complete open/close/SEAL coordination, not a HEAD check followed by vanilla new.
            ci.setReturnValue(replaymod$ownedZip.open());
        }
    }

    @Inject(method = "close()V", at = @At("HEAD"), cancellable = true,
            require = 1, expect = 1, allow = 1)
    private void replaymod$close(CallbackInfo ci) {
        if (replaymod$ownedZip != null) {
            // C: raw first-close receipt must remain sticky, including closes before terminal.
            replaymod$ownedZip.close();
            ci.cancel();
        }
    }
}
