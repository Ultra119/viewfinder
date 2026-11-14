package dev.xirreal.viewfinder.capture;

import dev.xirreal.viewfinder.Viewfinder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;

public final class GLDebugCallbackHandler {
   private static GLDebugMessageCallback debugCallback;
   private static boolean initialized = false;

   private GLDebugCallbackHandler() {}

   public static synchronized void init() {
      if (initialized) return;

      try {
         GLCapabilities caps = GL.getCapabilities();
         if (!caps.OpenGL43 && !caps.GL_KHR_debug) {
            Viewfinder.LOGGER.info("OpenGL 4.3 or KHR_debug not supported, skipping debug callback registration");
            return;
         }

         debugCallback = GLDebugMessageCallback.create((source, type, id, severity, length, message, userParam) -> {
            if (severity == GL43C.GL_DEBUG_SEVERITY_HIGH || type == GL43C.GL_DEBUG_TYPE_ERROR) {
               String text = GLDebugMessageCallback.getMessage(length, message);
               String pass = CurrentPass.getCurrentPath();
               if (pass == null || pass.isBlank()) {
                  pass = CurrentPass.getCurrentPass();
               }
               Viewfinder.getErrorCapture().addError("OpenGL Error", pass != null ? pass : "unknown", text, "");
            }
         });

         if (caps.OpenGL43) {
            GL11C.glEnable(GL43C.GL_DEBUG_OUTPUT);
            GL43C.glDebugMessageCallback(debugCallback, 0L);
         } else {
            GL11C.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
            KHRDebug.glDebugMessageCallback(debugCallback, 0L);
         }
         initialized = true;
         Viewfinder.LOGGER.info("Initialized OpenGL debug message callback handler");
      } catch (Throwable t) {
         Viewfinder.LOGGER.warn("Failed to initialize OpenGL debug message callback", t);
      }
   }
}
