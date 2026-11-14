package dev.xirreal.viewfinder.temporal;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public class FreezeController {

   private static volatile boolean cameraFrozen = false;
   private static volatile boolean jitterFrozen = false;
   private static Matrix4f frozenModelView = null;
   private static Matrix4f frozenProjection = null;

   public static boolean isCameraFrozen() {
      return cameraFrozen;
   }

   public static synchronized void setCameraFrozen(boolean frozen) {
      cameraFrozen = frozen;
      if (frozen) {
         try {
            Matrix4fc mv = CapturedRenderingState.INSTANCE.getGbufferModelView();
            if (mv != null) frozenModelView = new Matrix4f(mv);
            Matrix4fc proj = CapturedRenderingState.INSTANCE.getGbufferProjection();
            if (proj != null) frozenProjection = new Matrix4f(proj);
         } catch (Throwable ignored) {
            // Headless or Iris uninitialized
         }
      } else {
         frozenModelView = null;
         frozenProjection = null;
      }
   }

   public static boolean isJitterFrozen() {
      return jitterFrozen;
   }

   public static void setJitterFrozen(boolean frozen) {
      jitterFrozen = frozen;
   }

   public static void setAllFrozen(boolean frozen) {
      setCameraFrozen(frozen);
      setJitterFrozen(frozen);
   }

   public static synchronized Matrix4f getFrozenModelView() {
      return frozenModelView != null ? new Matrix4f(frozenModelView) : null;
   }

   public static synchronized Matrix4f getFrozenProjection() {
      return frozenProjection != null ? new Matrix4f(frozenProjection) : null;
   }

   public static synchronized void captureIfUnset(Matrix4fc modelView, Matrix4fc projection) {
      if (frozenModelView == null && modelView != null) frozenModelView = new Matrix4f(modelView);
      if (frozenProjection == null && projection != null) frozenProjection = new Matrix4f(projection);
   }

   public static void reset() {
      setAllFrozen(false);
   }
}
