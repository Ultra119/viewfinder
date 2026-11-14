package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.mixin.client.IrisRenderingPipelineAccessor;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferAccessor;
import dev.xirreal.viewfinder.mixin.client.ShaderStorageBufferHolderAccessor;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;

/** Captures the framebuffer attachments still bound when a named Iris debug group ends. */
public final class PassCaptureScheduler {
   private static volatile Request pending;

   private PassCaptureScheduler() {}

   public static synchronized CompletableFuture<JsonObject> schedule(String pass) {
      if (pending != null && !pending.result.isDone()) throw new IllegalStateException("A pass capture is already pending");
      if (pass == null || pass.isBlank()) throw new IllegalArgumentException("pass is required");
      Request request = new Request(pass, new CompletableFuture<>());
      pending = request;
      return request.result;
   }

   public static void onPassEnd() {
      Request request = pending;
      if (request == null) return;
      if (request.result.isDone()) {
         pending = null;
         return;
      }
      String path = CurrentPass.getCurrentPath();
      String leaf = CurrentPass.getCurrentPass();
      if (!request.pass.equals(path) && !request.pass.equals(leaf)) return;

      pending = null;
      try {
         request.result.complete(capture(path != null && !path.isBlank() ? path : leaf));
      } catch (Exception e) {
         request.result.completeExceptionally(e);
      }
   }

   public static void onComputeEnd(String name) {
      Request request = pending;
      if (request == null) return;
      if (request.result.isDone()) {
         pending = null;
         return;
      }
      String path = CurrentPass.getCurrentPath();
      String leaf = CurrentPass.getCurrentPass();
      if (!request.pass.equals(name) && !request.pass.equals(path) && !request.pass.equals(leaf)) return;

      pending = null;
      try {
         request.result.complete(capture(name));
      } catch (Exception e) {
         request.result.completeExceptionally(e);
      }
   }

   private static JsonObject capture(String pass) throws Exception {
      Path directory = CaptureStore.create("pass-output");
      JsonObject result = new JsonObject();
      result.addProperty("captureId", directory.getFileName().toString());
      result.addProperty("pass", pass);
      int framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
      result.addProperty("framebuffer", framebuffer);
      JsonArray outputs = new JsonArray();
      JsonArray images = new JsonArray();
      JsonArray ssbos = new JsonArray();

      // Memory barrier before CPU readback of textures, images, and SSBOs
      try {
         GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
      } catch (Throwable ignored) {}

      // Capture custom images
      var pipeline = Iris.getPipelineManager().getPipelineNullable();
      IrisRenderingPipelineAccessor accessor = (pipeline instanceof IrisRenderingPipeline irisPipeline)
         ? (IrisRenderingPipelineAccessor) irisPipeline
         : null;

      Set<GlImage> customImages = accessor != null ? accessor.getCustomImages() : null;
      if (customImages != null) {
         for (GlImage image : customImages) {
            Path output = directory.resolve("image_" + image.getName() + ".png");
            JsonObject dump = TextureDumper.dumpTexture(image.getId(), output.toString());
            dump.addProperty("name", image.getName());
            if (image.getSamplerName() != null) {
               dump.addProperty("samplerName", image.getSamplerName());
            }
            dump.addProperty("resourceUri", "viewfinder://capture/" + directory.getFileName() + "/" + output.getFileName());
            images.add(dump);
         }
      }

      // Capture active SSBOs
      try {
         List<?> activeBuffers = ShaderStorageBufferHolderAccessor.getActiveBuffers();
         if (activeBuffers != null) {
            for (Object buf : activeBuffers) {
               ShaderStorageBufferAccessor ssboAccessor = (ShaderStorageBufferAccessor) buf;
               int bufferId = ssboAccessor.getId();
               int index = ssboAccessor.getIndex();
               Path output = directory.resolve("ssbo_" + index + ".bin");
               JsonObject dump = SSBODumper.dumpBuffer(bufferId, index, output.toString());
               dump.addProperty("index", index);
               dump.addProperty("resourceUri", "viewfinder://capture/" + directory.getFileName() + "/" + output.getFileName());
               ssbos.add(dump);
            }
         }
      } catch (Throwable ignored) {}

      if (framebuffer == 0) {
         if (images.isEmpty() && ssbos.isEmpty()) {
            result.addProperty("skipped", true);
            result.addProperty("reason", "Pass ended with the default framebuffer, which has no texture attachments, custom images, or SSBOs");
         }
         result.add("outputs", outputs);
         result.add("images", images);
         result.add("ssbos", ssbos);
         return result;
      }

      int max = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
      for (int index = 0; index < max; index++) {
         int attachment = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0 + index);
         if (attachment == GL11C.GL_NONE) continue;
         captureAttachment(directory, outputs, attachment, "color" + index);
      }
      captureAttachment(directory, outputs, GL30C.GL_DEPTH_ATTACHMENT, "depth");
      result.add("outputs", outputs);
      result.add("images", images);
      result.add("ssbos", ssbos);
      return result;
   }

   private static void captureAttachment(Path directory, JsonArray outputs, int attachment, String name) {
      int type = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
         GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (type != GL11C.GL_TEXTURE) return;
      int texture = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
         GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      Path output = directory.resolve(name + ".png");
      JsonObject dump = TextureDumper.dumpTexture(texture, output.toString());
      dump.addProperty("attachment", name);
      dump.addProperty("resourceUri", "viewfinder://capture/" + directory.getFileName() + "/" + output.getFileName());
      outputs.add(dump);
   }

   private record Request(String pass, CompletableFuture<JsonObject> result) {}
}
