package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.capture.CurrentPass;
import dev.xirreal.viewfinder.capture.GLDebugCallbackHandler;
import dev.xirreal.viewfinder.capture.PassCaptureScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class MixinIrisRenderingPipeline {

   @Inject(method = "beginLevelRendering()V", at = @At(value = "FIELD", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;setup:[Lnet/irisshaders/iris/gl/program/ComputeProgram;", ordinal = 0), remap = false)
   private void viewfinder$beforeSetupComputes(CallbackInfo ci) {
      GLDebugCallbackHandler.init();
      CurrentPass.push("setup");
   }

   @Inject(method = "beginLevelRendering()V", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V", ordinal = 0), remap = false)
   private void viewfinder$afterSetupComputes(CallbackInfo ci) {
      PassCaptureScheduler.onPassEnd();
      CurrentPass.pop();
   }
}
