package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class TextureInspector {

   private static final long MAX_TEXTURE_READ_BYTES = 64L * 1024L * 1024L;
   private static final int DEFAULT_SAMPLE_COUNT = 4096;
   private static final int MAX_SAMPLE_COUNT = 1_000_000;

   public static int defaultSampleCount() {
      return DEFAULT_SAMPLE_COUNT;
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      ByteBuffer buffer
   ) {
      TextureDumper.readSubImage(textureId, target, level, x, y, z, width, height, depth, format, type, buffer);
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      IntBuffer buffer
   ) {
      TextureDumper.readSubImage(textureId, target, level, x, y, z, width, height, depth, format, type, buffer);
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      FloatBuffer buffer
   ) {
      TextureDumper.readSubImage(textureId, target, level, x, y, z, width, height, depth, format, type, buffer);
   }

   public static JsonObject inspectByName(String name, int maxSamples) {
      return inspectByName(name, maxSamples, -1, -1, 0, null, "Z", false);
   }

   public static JsonObject inspectByName(String name, int maxSamples, int x, int y, int z) {
      return inspectByName(name, maxSamples, x, y, z, null, "Z", false);
   }

   public static JsonObject inspectByName(
      String name,
      int maxSamples,
      int x,
      int y,
      int z,
      Integer slice,
      String axis,
      boolean voxelSummary
   ) {
      int textureId = TextureResolver.resolveTexture(name);
      if (textureId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "Unknown texture: " + name);
         return error;
      }

      JsonObject result = inspectTexture(textureId, maxSamples, x, y, z, slice, axis, voxelSummary);
      result.addProperty("name", name);
      return result;
   }

   public static JsonObject inspectTexture(int textureId, int maxSamples) {
      return inspectTexture(textureId, maxSamples, -1, -1, 0, null, "Z", false);
   }

   public static JsonObject inspectTexture(int textureId, int maxSamples, int x, int y, int z) {
      return inspectTexture(textureId, maxSamples, x, y, z, null, "Z", false);
   }

   public static JsonObject inspectTexture(
      int textureId,
      int maxSamples,
      int x,
      int y,
      int z,
      Integer slice,
      String axis,
      boolean voxelSummary
   ) {
      JsonObject result = describeTexture(textureId);
      if (result.has("error")) {
         return result;
      }

      int target = result.get("target").getAsInt();
      int width = result.get("width").getAsInt();
      int height = result.get("height").getAsInt();
      int depth = result.get("depth").getAsInt();
      boolean integer = result.get("integer").getAsBoolean();
      boolean signed = result.get("signed").getAsBoolean();
      int components = result.get("components").getAsInt();
      int readComponents = integer ? components : 4;
      long totalPixels = (long) width * height * depth;
      long estimatedBytes = totalPixels * readComponents * Integer.BYTES;

      result.addProperty("readComponents", readComponents);
      result.addProperty("estimatedReadBytes", estimatedBytes);
      result.addProperty("maxReadBytes", MAX_TEXTURE_READ_BYTES);
      result.addProperty("maxSampleCount", MAX_SAMPLE_COUNT);

      if (slice != null) {
         result.addProperty("slice", slice);
      }
      if (axis != null) {
         result.addProperty("axis", axis.toUpperCase(Locale.ROOT));
      }

      int previousTexture = GL43C.glGetInteger(TextureDumper.getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         // Subvolume point queries (16 bytes each)
         readPointQueries(result, textureId, target, width, height, depth, components, integer, signed, x, y, z);

         // Channel statistics using streaming slice readback
         if (integer) {
            streamIntegerStats(result, textureId, target, width, height, depth, components, signed, maxSamples);
         } else {
            streamFloatStats(result, textureId, target, width, height, depth, maxSamples);
         }

         // Voxel grid diagnostics summary if requested
         if (voxelSummary) {
            JsonObject summary = streamVoxelSummary(textureId, target, width, height, depth, components, integer, signed);
            result.add("voxelSummary", summary);
         }
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   public static JsonObject describeTexture(int textureId) {
      JsonObject result = new JsonObject();
      result.addProperty("textureId", textureId);
      int target = TextureDumper.detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(TextureDumper.getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = target == GL43C.GL_TEXTURE_3D ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;
         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean integer = TextureDumper.isIntegerFormat(internalFormat);
         result.addProperty("target", target);
         result.addProperty("targetName", targetName(target));
         result.addProperty("width", width);
         result.addProperty("height", height);
         result.addProperty("depth", depth);
         result.addProperty("totalPixels", (long) width * height * depth);
         result.addProperty("internalFormat", internalFormat);
         result.addProperty("formatName", TextureDumper.getFormatName(internalFormat));
         result.addProperty("components", TextureDumper.getComponentCount(internalFormat));
         result.addProperty("integer", integer);
         result.addProperty("signed", integer && TextureDumper.isSignedFormat(internalFormat));
         result.addProperty("statsAvailable", false);
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   private static void readPointQueries(
      JsonObject result,
      int textureId,
      int target,
      int width,
      int height,
      int depth,
      int components,
      boolean isInteger,
      boolean signed,
      int x,
      int y,
      int z
   ) {
      if (isInteger) {
         int pixelFormat = TextureDumper.integerPixelFormat(components);
         int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
         IntBuffer pixelBuf = MemoryUtil.memAllocInt(components);
         try {
            readSubImage(textureId, target, 0, 0, 0, 0, 1, 1, 1, pixelFormat, pixelType, pixelBuf);
            result.add("firstPixel", integerPixel(pixelBuf, components, 0, signed));

            readSubImage(textureId, target, 0, width / 2, height / 2, depth / 2, 1, 1, 1, pixelFormat, pixelType, pixelBuf);
            result.add("centerPixel", integerPixel(pixelBuf, components, 0, signed));

            if (x >= 0 && y >= 0) {
               JsonObject requested = requestedPixelHeader(width, height, depth, x, y, z);
               if (requested.get("valid").getAsBoolean()) {
                  readSubImage(textureId, target, 0, x, y, z, 1, 1, 1, pixelFormat, pixelType, pixelBuf);
                  requested.add("value", integerPixel(pixelBuf, components, 0, signed));
               }
               result.add("requestedPixel", requested);
            }
         } finally {
            MemoryUtil.memFree(pixelBuf);
         }
      } else {
         FloatBuffer pixelBuf = MemoryUtil.memAllocFloat(4);
         try {
            readSubImage(textureId, target, 0, 0, 0, 0, 1, 1, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, pixelBuf);
            result.add("firstPixel", floatPixel(pixelBuf, 0));

            readSubImage(textureId, target, 0, width / 2, height / 2, depth / 2, 1, 1, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, pixelBuf);
            result.add("centerPixel", floatPixel(pixelBuf, 0));

            if (x >= 0 && y >= 0) {
               JsonObject requested = requestedPixelHeader(width, height, depth, x, y, z);
               if (requested.get("valid").getAsBoolean()) {
                  readSubImage(textureId, target, 0, x, y, z, 1, 1, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, pixelBuf);
                  requested.add("value", floatPixel(pixelBuf, 0));
               }
               result.add("requestedPixel", requested);
            }
         } finally {
            MemoryUtil.memFree(pixelBuf);
         }
      }
   }

   private static void streamIntegerStats(
      JsonObject result,
      int textureId,
      int target,
      int width,
      int height,
      int depth,
      int components,
      boolean signed,
      int maxSamples
   ) {
      long totalPixels = (long) width * height * depth;
      int samples = sampleCount(totalPixels, maxSamples);
      ChannelAccumulator[] accumulators = new ChannelAccumulator[components];
      for (int i = 0; i < components; i++) {
         accumulators[i] = new ChannelAccumulator();
      }

      long pixelsPerLayer = (long) width * height;
      int pixelFormat = TextureDumper.integerPixelFormat(components);
      int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
      int maxBufferBytes = 1024 * 1024;
      long sliceBytes = pixelsPerLayer * components * 4L;

      if (sliceBytes <= maxBufferBytes) {
         IntBuffer sliceBuffer = MemoryUtil.memAllocInt(Math.toIntExact(pixelsPerLayer * components));
         try {
            int currentSample = 0;
            for (int z = 0; z < depth && currentSample < samples; z++) {
               long layerStartPixel = z * pixelsPerLayer;
               long layerEndPixel = layerStartPixel + pixelsPerLayer;

               long nextSamplePixel = samplePixel(totalPixels, samples, currentSample);
               if (nextSamplePixel >= layerEndPixel) {
                  continue;
               }

               readSubImage(textureId, target, 0, 0, 0, z, width, height, 1, pixelFormat, pixelType, sliceBuffer);

               while (currentSample < samples) {
                  long p = samplePixel(totalPixels, samples, currentSample);
                  if (p >= layerEndPixel) {
                     break;
                  }
                  int inLayerOffset = Math.toIntExact((p - layerStartPixel) * components);
                  for (int c = 0; c < components; c++) {
                     double v = signed ? sliceBuffer.get(inLayerOffset + c) : Integer.toUnsignedLong(sliceBuffer.get(inLayerOffset + c));
                     accumulators[c].add(v);
                  }
                  currentSample++;
               }
            }
         } finally {
            MemoryUtil.memFree(sliceBuffer);
         }
      } else {
         IntBuffer pointBuf = MemoryUtil.memAllocInt(components);
         try {
            for (int sample = 0; sample < samples; sample++) {
               long p = samplePixel(totalPixels, samples, sample);
               int z = (int) (p / pixelsPerLayer);
               long inLayer = p % pixelsPerLayer;
               int y = (int) (inLayer / width);
               int x = (int) (inLayer % width);
               readSubImage(textureId, target, 0, x, y, z, 1, 1, 1, pixelFormat, pixelType, pointBuf);
               for (int c = 0; c < components; c++) {
                  double v = signed ? pointBuf.get(c) : Integer.toUnsignedLong(pointBuf.get(c));
                  accumulators[c].add(v);
               }
            }
         } finally {
            MemoryUtil.memFree(pointBuf);
         }
      }

      result.addProperty("statsAvailable", true);
      result.addProperty("sampledPixels", samples);
      result.addProperty("truncated", samples < totalPixels);
      result.add("channels", channelStats(accumulators));
   }

   private static void streamFloatStats(
      JsonObject result,
      int textureId,
      int target,
      int width,
      int height,
      int depth,
      int maxSamples
   ) {
      long totalPixels = (long) width * height * depth;
      int samples = sampleCount(totalPixels, maxSamples);
      ChannelAccumulator[] accumulators = new ChannelAccumulator[4];
      for (int i = 0; i < 4; i++) {
         accumulators[i] = new ChannelAccumulator();
      }

      long pixelsPerLayer = (long) width * height;
      int maxBufferBytes = 1024 * 1024;
      long sliceBytes = pixelsPerLayer * 16L;

      if (sliceBytes <= maxBufferBytes) {
         FloatBuffer sliceBuffer = MemoryUtil.memAllocFloat(Math.toIntExact(pixelsPerLayer * 4));
         try {
            int currentSample = 0;
            for (int z = 0; z < depth && currentSample < samples; z++) {
               long layerStartPixel = z * pixelsPerLayer;
               long layerEndPixel = layerStartPixel + pixelsPerLayer;

               long nextSamplePixel = samplePixel(totalPixels, samples, currentSample);
               if (nextSamplePixel >= layerEndPixel) {
                  continue;
               }

               readSubImage(textureId, target, 0, 0, 0, z, width, height, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, sliceBuffer);

               while (currentSample < samples) {
                  long p = samplePixel(totalPixels, samples, currentSample);
                  if (p >= layerEndPixel) {
                     break;
                  }
                  int inLayerOffset = Math.toIntExact((p - layerStartPixel) * 4);
                  for (int c = 0; c < 4; c++) {
                     accumulators[c].add(sliceBuffer.get(inLayerOffset + c));
                  }
                  currentSample++;
               }
            }
         } finally {
            MemoryUtil.memFree(sliceBuffer);
         }
      } else {
         FloatBuffer pointBuf = MemoryUtil.memAllocFloat(4);
         try {
            for (int sample = 0; sample < samples; sample++) {
               long p = samplePixel(totalPixels, samples, sample);
               int z = (int) (p / pixelsPerLayer);
               long inLayer = p % pixelsPerLayer;
               int y = (int) (inLayer / width);
               int x = (int) (inLayer % width);
               readSubImage(textureId, target, 0, x, y, z, 1, 1, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, pointBuf);
               for (int c = 0; c < 4; c++) {
                  accumulators[c].add(pointBuf.get(c));
               }
            }
         } finally {
            MemoryUtil.memFree(pointBuf);
         }
      }

      result.addProperty("statsAvailable", true);
      result.addProperty("sampledPixels", samples);
      result.addProperty("truncated", samples < totalPixels);
      result.add("channels", channelStats(accumulators));
   }

   public static JsonObject streamVoxelSummary(
      int textureId,
      int target,
      int width,
      int height,
      int depth,
      int components,
      boolean isInteger,
      boolean signed
   ) {
      long totalVoxels = (long) width * height * depth;
      long occupiedVoxels = 0;
      int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
      int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
      int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
      Map<Integer, Long> idCounts = new HashMap<>();

      int slicePixels = width * height;
      int bytesPerPixel = isInteger ? 4 * components : 16;
      long sliceBytes = (long) slicePixels * bytesPerPixel;
      int maxBufferBytes = 1024 * 1024;

      if (isInteger) {
         int pixelFormat = TextureDumper.integerPixelFormat(components);
         int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;

         if (sliceBytes <= maxBufferBytes) {
            IntBuffer sliceBuffer = MemoryUtil.memAllocInt(slicePixels * components);
            try {
               for (int z = 0; z < depth; z++) {
                  readSubImage(textureId, target, 0, 0, 0, z, width, height, 1, pixelFormat, pixelType, sliceBuffer);
                  for (int y = 0; y < height; y++) {
                     for (int x = 0; x < width; x++) {
                        int offset = (y * width + x) * components;
                        int id = signed ? sliceBuffer.get(offset) : (int) Integer.toUnsignedLong(sliceBuffer.get(offset));
                        if (id != 0) {
                           occupiedVoxels++;
                           minX = Math.min(minX, x);
                           maxX = Math.max(maxX, x);
                           minY = Math.min(minY, y);
                           maxY = Math.max(maxY, y);
                           minZ = Math.min(minZ, z);
                           maxZ = Math.max(maxZ, z);
                           idCounts.merge(id, 1L, Long::sum);
                        }
                     }
                  }
               }
            } finally {
               MemoryUtil.memFree(sliceBuffer);
            }
         } else {
            int rowsPerChunk = Math.max(1, maxBufferBytes / (width * components * 4));
            IntBuffer chunkBuffer = MemoryUtil.memAllocInt(rowsPerChunk * width * components);
            try {
               for (int z = 0; z < depth; z++) {
                  for (int startY = 0; startY < height; startY += rowsPerChunk) {
                     int chunkH = Math.min(rowsPerChunk, height - startY);
                     readSubImage(textureId, target, 0, 0, startY, z, width, chunkH, 1, pixelFormat, pixelType, chunkBuffer);
                     for (int cy = 0; cy < chunkH; cy++) {
                        int y = startY + cy;
                        for (int x = 0; x < width; x++) {
                           int offset = (cy * width + x) * components;
                           int id = signed ? chunkBuffer.get(offset) : (int) Integer.toUnsignedLong(chunkBuffer.get(offset));
                           if (id != 0) {
                              occupiedVoxels++;
                              minX = Math.min(minX, x);
                              maxX = Math.max(maxX, x);
                              minY = Math.min(minY, y);
                              maxY = Math.max(maxY, y);
                              minZ = Math.min(minZ, z);
                              maxZ = Math.max(maxZ, z);
                              idCounts.merge(id, 1L, Long::sum);
                           }
                        }
                     }
                  }
               }
            } finally {
               MemoryUtil.memFree(chunkBuffer);
            }
         }
      } else {
         if (sliceBytes <= maxBufferBytes) {
            FloatBuffer sliceBuffer = MemoryUtil.memAllocFloat(slicePixels * 4);
            try {
               for (int z = 0; z < depth; z++) {
                  readSubImage(textureId, target, 0, 0, 0, z, width, height, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, sliceBuffer);
                  for (int y = 0; y < height; y++) {
                     for (int x = 0; x < width; x++) {
                        int offset = (y * width + x) * 4;
                        int id = (int) sliceBuffer.get(offset);
                        if (id != 0) {
                           occupiedVoxels++;
                           minX = Math.min(minX, x);
                           maxX = Math.max(maxX, x);
                           minY = Math.min(minY, y);
                           maxY = Math.max(maxY, y);
                           minZ = Math.min(minZ, z);
                           maxZ = Math.max(maxZ, z);
                           idCounts.merge(id, 1L, Long::sum);
                        }
                     }
                  }
               }
            } finally {
               MemoryUtil.memFree(sliceBuffer);
            }
         } else {
            int rowsPerChunk = Math.max(1, maxBufferBytes / (width * 16));
            FloatBuffer chunkBuffer = MemoryUtil.memAllocFloat(rowsPerChunk * width * 4);
            try {
               for (int z = 0; z < depth; z++) {
                  for (int startY = 0; startY < height; startY += rowsPerChunk) {
                     int chunkH = Math.min(rowsPerChunk, height - startY);
                     readSubImage(textureId, target, 0, 0, startY, z, width, chunkH, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, chunkBuffer);
                     for (int cy = 0; cy < chunkH; cy++) {
                        int y = startY + cy;
                        for (int x = 0; x < width; x++) {
                           int offset = (cy * width + x) * 4;
                           int id = (int) chunkBuffer.get(offset);
                           if (id != 0) {
                              occupiedVoxels++;
                              minX = Math.min(minX, x);
                              maxX = Math.max(maxX, x);
                              minY = Math.min(minY, y);
                              maxY = Math.max(maxY, y);
                              minZ = Math.min(minZ, z);
                              maxZ = Math.max(maxZ, z);
                              idCounts.merge(id, 1L, Long::sum);
                           }
                        }
                     }
                  }
               }
            } finally {
               MemoryUtil.memFree(chunkBuffer);
            }
         }
      }

      return buildVoxelSummary(width, height, depth, totalVoxels, occupiedVoxels, minX, maxX, minY, maxY, minZ, maxZ, idCounts);
   }

   public static JsonObject computeVoxelSummaryFromGrid(int width, int height, int depth, int[] grid) {
      long totalVoxels = (long) width * height * depth;
      long occupiedVoxels = 0;
      int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
      int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
      int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
      Map<Integer, Long> idCounts = new HashMap<>();

      for (int z = 0; z < depth; z++) {
         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int id = grid[(z * height + y) * width + x];
               if (id != 0) {
                  occupiedVoxels++;
                  minX = Math.min(minX, x);
                  maxX = Math.max(maxX, x);
                  minY = Math.min(minY, y);
                  maxY = Math.max(maxY, y);
                  minZ = Math.min(minZ, z);
                  maxZ = Math.max(maxZ, z);
                  idCounts.merge(id, 1L, Long::sum);
               }
            }
         }
      }
      return buildVoxelSummary(width, height, depth, totalVoxels, occupiedVoxels, minX, maxX, minY, maxY, minZ, maxZ, idCounts);
   }

   public static JsonObject buildVoxelSummary(
      int width,
      int height,
      int depth,
      long totalVoxels,
      long occupiedVoxels,
      int minX,
      int maxX,
      int minY,
      int maxY,
      int minZ,
      int maxZ,
      Map<Integer, Long> idCounts
   ) {
      JsonObject summary = new JsonObject();
      summary.addProperty("totalVoxels", totalVoxels);
      summary.addProperty("occupiedVoxels", occupiedVoxels);
      double occupancyRatio = totalVoxels > 0 ? (double) occupiedVoxels / totalVoxels : 0.0;
      summary.addProperty("occupancyRatio", occupancyRatio);
      summary.addProperty("occupancyPercent", String.format(Locale.ROOT, "%.2f%%", occupancyRatio * 100.0));

      JsonObject aabb = new JsonObject();
      if (occupiedVoxels > 0) {
         JsonArray min = new JsonArray();
         min.add(minX);
         min.add(minY);
         min.add(minZ);
         aabb.add("min", min);

         JsonArray max = new JsonArray();
         max.add(maxX);
         max.add(maxY);
         max.add(maxZ);
         aabb.add("max", max);

         int sizeX = maxX - minX + 1;
         int sizeY = maxY - minY + 1;
         int sizeZ = maxZ - minZ + 1;
         JsonArray size = new JsonArray();
         size.add(sizeX);
         size.add(sizeY);
         size.add(sizeZ);
         aabb.add("size", size);
         aabb.addProperty("volume", (long) sizeX * sizeY * sizeZ);
      } else {
         aabb.add("min", JsonNull.INSTANCE);
         aabb.add("max", JsonNull.INSTANCE);
         JsonArray size = new JsonArray();
         size.add(0);
         size.add(0);
         size.add(0);
         aabb.add("size", size);
         aabb.addProperty("volume", 0L);
      }
      summary.add("activeBoundingBox", aabb);
      summary.addProperty("uniqueBlockIds", idCounts.size());

      JsonArray histogram = new JsonArray();
      List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(idCounts.entrySet());
      sorted.sort((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()));

      for (Map.Entry<Integer, Long> entry : sorted) {
         JsonObject item = new JsonObject();
         item.addProperty("id", entry.getKey());
         item.addProperty("count", entry.getValue());
         double pct = occupiedVoxels > 0 ? (entry.getValue() * 100.0) / occupiedVoxels : 0.0;
         item.addProperty("percentage", Math.round(pct * 100.0) / 100.0);
         histogram.add(item);
      }
      summary.add("histogram", histogram);

      return summary;
   }

   private static JsonObject requestedPixelHeader(int width, int height, int depth, int x, int y, int z) {
      JsonObject requested = new JsonObject();
      requested.addProperty("x", x);
      requested.addProperty("y", y);
      requested.addProperty("z", z);
      boolean valid = x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth;
      requested.addProperty("valid", valid);
      if (!valid) {
         requested.addProperty("error", "Requested pixel is outside texture bounds");
      }
      return requested;
   }

   private static JsonArray integerPixel(IntBuffer buffer, int components, long pixel, boolean signed) {
      JsonArray values = new JsonArray();
      int offset = Math.toIntExact(pixel * components);
      for (int component = 0; component < components; component++) {
         if (signed) {
            values.add(buffer.get(offset + component));
         } else {
            values.add(Integer.toUnsignedLong(buffer.get(offset + component)));
         }
      }
      return values;
   }

   private static JsonArray floatPixel(FloatBuffer buffer, long pixel) {
      JsonArray values = new JsonArray();
      int offset = Math.toIntExact(pixel * 4L);
      for (int component = 0; component < 4; component++) {
         float value = buffer.get(offset + component);
         if (Float.isFinite(value)) {
            values.add(value);
         } else if (Float.isNaN(value)) {
            values.add("NaN");
         } else {
            values.add(value > 0.0f ? "Infinity" : "-Infinity");
         }
      }
      return values;
   }

   private static JsonArray channelStats(ChannelAccumulator[] accumulators) {
      JsonArray channels = new JsonArray();
      for (int i = 0; i < accumulators.length; i++) {
         ChannelAccumulator accumulator = accumulators[i];
         JsonObject channel = new JsonObject();
         channel.addProperty("index", i);
         channel.addProperty("finiteCount", accumulator.finiteCount);
         channel.addProperty("nanCount", accumulator.nanCount);
         channel.addProperty("infiniteCount", accumulator.infiniteCount);
         if (accumulator.finiteCount > 0) {
            channel.addProperty("min", accumulator.min);
            channel.addProperty("max", accumulator.max);
            channel.addProperty("avg", accumulator.sum / accumulator.finiteCount);
         } else {
            channel.add("min", JsonNull.INSTANCE);
            channel.add("max", JsonNull.INSTANCE);
            channel.add("avg", JsonNull.INSTANCE);
         }
         channels.add(channel);
      }
      return channels;
   }

   private static int sampleCount(long totalPixels, int maxSamples) {
      if (totalPixels <= 0L) {
         return 0;
      }
      int clampedMax = Math.max(1, Math.min(maxSamples, MAX_SAMPLE_COUNT));
      return (int) Math.min(totalPixels, clampedMax);
   }

   private static long samplePixel(long totalPixels, int samples, int sample) {
      if (samples <= 1) {
         return 0L;
      }
      return Math.min(totalPixels - 1L, (long) Math.floor(sample * (totalPixels / (double) samples)));
   }

   private static String targetName(int target) {
      return switch (target) {
         case GL43C.GL_TEXTURE_2D -> "GL_TEXTURE_2D";
         case GL43C.GL_TEXTURE_3D -> "GL_TEXTURE_3D";
         default -> "0x" + Integer.toHexString(target);
      };
   }

   private static class ChannelAccumulator {

      private double min = Double.POSITIVE_INFINITY;
      private double max = Double.NEGATIVE_INFINITY;
      private double sum;
      private int finiteCount;
      private int nanCount;
      private int infiniteCount;

      private void add(double value) {
         if (Double.isNaN(value)) {
            nanCount++;
            return;
         }
         if (Double.isInfinite(value)) {
            infiniteCount++;
            return;
         }
         min = Math.min(min, value);
         max = Math.max(max, value);
         sum += value;
         finiteCount++;
      }
   }
}
