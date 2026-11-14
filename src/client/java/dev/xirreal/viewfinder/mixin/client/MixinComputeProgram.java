package dev.xirreal.viewfinder.mixin.client;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.capture.CurrentPass;
import dev.xirreal.viewfinder.capture.PassCaptureScheduler;
import dev.xirreal.viewfinder.capture.ProgramRegistry;
import org.lwjgl.opengl.GL42C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.gl.program.ComputeProgram", remap = false)
public abstract class MixinComputeProgram {

   @Shadow(remap = false)
   public abstract int getProgramId();

   @Inject(method = "<init>", at = @At("RETURN"), remap = false)
   private void viewfinder$onInit(CallbackInfo ci) {
      ProgramRegistry.registerCompute(getProgramId());
   }

   @Inject(method = "dispatch(FF)V", at = @At("HEAD"), remap = false)
   private void viewfinder$beforeDispatch(float width, float height, CallbackInfo ci) {
      String passName = ProgramRegistry.getName(getProgramId());
      if (passName == null || passName.isBlank()) {
         passName = CurrentPass.getCurrentPass();
      }
      if (passName == null || passName.isBlank()) {
         passName = "compute_" + getProgramId();
      }
      Viewfinder.getMetricsCollector().beginComputeTiming(passName);
   }

   @Inject(method = "dispatch(FF)V", at = @At("RETURN"), remap = false)
   private void viewfinder$afterDispatch(float width, float height, CallbackInfo ci) {
      Viewfinder.getMetricsCollector().endComputeTiming();
      try {
         GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
      } catch (Throwable ignored) {}

      String passName = ProgramRegistry.getName(getProgramId());
      if (passName != null && !passName.isBlank()) {
         PassCaptureScheduler.onComputeEnd(passName);
      }
   }
}
