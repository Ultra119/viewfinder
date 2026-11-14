package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.temporal.FreezeController;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.uniforms.CapturedRenderingState", remap = false)
public class MixinLevelRenderer {

   @Inject(method = "setGbufferModelView", at = @At("HEAD"), cancellable = true)
   private void viewfinder$freezeModelView(Matrix4fc matrix, CallbackInfo ci) {
      if (FreezeController.isCameraFrozen()) {
         FreezeController.captureIfUnset(matrix, null);
         ci.cancel();
      }
   }

   @Inject(method = "setGbufferProjection", at = @At("HEAD"), cancellable = true)
   private void viewfinder$freezeProjection(Matrix4f matrix, CallbackInfo ci) {
      if (FreezeController.isCameraFrozen()) {
         FreezeController.captureIfUnset(null, matrix);
         ci.cancel();
      }
   }
}
