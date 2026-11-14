package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.temporal.FreezeController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
public class MixinIrisTime {

   @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
   private void viewfinder$onBeginFrame(CallbackInfo ci) {
      if (FreezeController.isJitterFrozen()) {
         ci.cancel();
      }
   }
}
