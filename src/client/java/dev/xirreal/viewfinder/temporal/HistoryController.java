package dev.xirreal.viewfinder.temporal;

import dev.xirreal.viewfinder.Viewfinder;
import dev.xirreal.viewfinder.mixin.client.IrisRenderingPipelineAccessor;
import java.nio.ByteBuffer;
import java.util.Set;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;

public class HistoryController {

   public static int flushHistory() {
      try {
         var pipelineManager = Iris.getPipelineManager();
         if (pipelineManager == null) return 0;
         var pipeline = pipelineManager.getPipelineNullable();
         if (!(pipeline instanceof IrisRenderingPipeline irisPipeline)) {
            return 0;
         }

         IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) irisPipeline;
         int flushedCount = 0;

         // 1. Flush ping-pong alt textures in RenderTargets (colortexN_alt)
         RenderTargets targets = accessor.getRenderTargets();
         if (targets != null) {
            for (int i = 0; i < targets.getRenderTargetCount(); i++) {
               RenderTarget target = targets.get(i);
               if (target != null) {
                  int altTex = target.getAltTexture();
                  if (altTex > 0) {
                     InternalTextureFormat internalFormat = target.getInternalFormat();
                     PixelFormat pixelFormat = internalFormat != null ? internalFormat.getPixelFormat() : PixelFormat.RGBA;
                     int glFormat = pixelFormat != null ? pixelFormat.getGlFormat() : GL11C.GL_RGBA;
                     int glType = (pixelFormat != null && pixelFormat.isInteger()) ? GL11C.GL_UNSIGNED_INT : GL11C.GL_FLOAT;
                     clearTexture(altTex, GL11C.GL_TEXTURE_2D, 0, glFormat, glType, target.getWidth(), target.getHeight(), 1);
                     flushedCount++;
                  }
               }
            }
         }

         // 2. Flush custom images (GlImage)
         Set<GlImage> customImages = accessor.getCustomImages();
         if (customImages != null) {
            for (GlImage image : customImages) {
               if (image == null) continue;
               int imageId = image.getId();
               if (imageId > 0) {
                  int targetType = image.getTarget() != null ? image.getTarget().getGlType() : GL11C.GL_TEXTURE_2D;
                  int format = image.getFormat() != null ? image.getFormat().getGlFormat() : GL11C.GL_RGBA;
                  int type = image.getPixelType() != null ? image.getPixelType().getGlFormat() : GL11C.GL_FLOAT;
                  clearTexture(imageId, targetType, 0, format, type, 1, 1, 0);
                  flushedCount++;
               }
            }
         }

         Viewfinder.LOGGER.info("History flush completed: reset {} history textures", flushedCount);
         return flushedCount;
      } catch (Throwable t) {
         Viewfinder.LOGGER.warn("History flush encountered error: {}", t.getMessage());
         return 0;
      }
   }

   public static void clearTexture(int textureId, int target, int level, int format, int type, int width, int height, int depth) {
      if (textureId <= 0) return;
      try {
         GLCapabilities caps = GL.getCapabilities();
         if (caps.OpenGL44 || caps.GL_ARB_clear_texture) {
            GL44C.glClearTexImage(textureId, level, format, type, (ByteBuffer) null);
            return;
         }

         // Fallback: Bind scratch FBO, attach texture, glClear(GL_COLOR_BUFFER_BIT)
         int prevFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
         int scratchFbo = GL30C.glGenFramebuffers();
         try {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, scratchFbo);
            if (target == GL12C.GL_TEXTURE_3D) {
               int d = depth > 0 ? depth : GL43C.glGetTexLevelParameteri(target, level, GL12C.GL_TEXTURE_DEPTH);
               for (int layer = 0; layer < d; layer++) {
                  GL30C.glFramebufferTextureLayer(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, textureId, level, layer);
                  GL11C.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                  GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
               }
            } else {
               GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, target, textureId, level);
               GL11C.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
               GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            }
         } finally {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevFbo);
            GL30C.glDeleteFramebuffers(scratchFbo);
         }
      } catch (Throwable t) {
         Viewfinder.LOGGER.warn("Failed to clear texture {}: {}", textureId, t.getMessage());
      }
   }
}
