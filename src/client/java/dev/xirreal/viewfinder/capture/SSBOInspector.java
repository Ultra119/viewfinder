package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class SSBOInspector {

   private static final int DEFAULT_PREVIEW_BYTES = 256;
   private static final int MAX_PREVIEW_BYTES = 64 * 1024;
   private static final int MAX_PREVIEW_VALUES = 32;
   private static final int SCAN_CHUNK_BYTES = 64 * 1024;
   private static final int DEFAULT_MAX_OFFSETS_TO_REPORT = 64;

   public static int defaultPreviewBytes() {
      return DEFAULT_PREVIEW_BYTES;
   }

   public static JsonObject inspectIndex(int index, int requestedBytes) {
      return inspectIndex(index, 0, requestedBytes);
   }

   public static JsonObject inspectIndex(int index, int offsetBytes, int lengthBytes) {
      int bufferId = SSBOResolver.resolveBufferId(index);
      if (bufferId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "No SSBO found at index " + index);
         return error;
      }

      return inspectBuffer(bufferId, index, offsetBytes, lengthBytes);
   }

   public static JsonObject inspectBuffer(int bufferId, int index, int requestedBytes) {
      return inspectBuffer(bufferId, index, 0, requestedBytes);
   }

   public static JsonObject inspectBuffer(int bufferId, int index, int offsetBytes, int lengthBytes) {
      JsonObject result = new JsonObject();
      result.addProperty("index", index);
      result.addProperty("bufferId", bufferId);

      int offset = Math.max(0, offsetBytes);
      result.addProperty("offsetBytes", offset);

      int previousBuffer = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
      ByteBuffer data = null;
      try {
         GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferId);

         int size = GL43C.glGetBufferParameteri(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_BUFFER_SIZE);
         result.addProperty("totalBytes", size);
         if (size <= 0) {
            result.addProperty("inspectedBytes", 0);
            result.addProperty("error", "Buffer " + bufferId + " is empty or invalid");
            return result;
         }

         if (offset >= size) {
            result.addProperty("inspectedBytes", 0);
            result.addProperty("maxPreviewBytes", MAX_PREVIEW_BYTES);
            result.addProperty("truncated", false);
            JsonObject emptyPreview = new JsonObject();
            emptyPreview.addProperty("hex", "");
            emptyPreview.add("uint8", new JsonArray());
            emptyPreview.add("int32", new JsonArray());
            emptyPreview.add("uint32", new JsonArray());
            emptyPreview.add("float32", new JsonArray());
            JsonObject emptyStats = new JsonObject();
            emptyStats.addProperty("valuesRead", 0);
            emptyStats.addProperty("finiteCount", 0);
            emptyStats.addProperty("nanCount", 0);
            emptyStats.addProperty("infiniteCount", 0);
            emptyPreview.add("float32Stats", emptyStats);
            result.add("preview", emptyPreview);
            return result;
         }

         int remaining = size - offset;
         int previewBytes = Math.min(remaining, Math.max(1, Math.min(lengthBytes, MAX_PREVIEW_BYTES)));
         data = MemoryUtil.memAlloc(previewBytes).order(ByteOrder.nativeOrder());
         GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offset, data);

         result.addProperty("inspectedBytes", previewBytes);
         result.addProperty("maxPreviewBytes", MAX_PREVIEW_BYTES);
         result.addProperty("truncated", previewBytes < remaining || offset > 0);
         result.add("preview", preview(data, previewBytes));
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         if (data != null) {
            MemoryUtil.memFree(data);
         }
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previousBuffer);
      }

      return result;
   }

   public static JsonObject scanIndex(int index) {
      int bufferId = SSBOResolver.resolveBufferId(index);
      if (bufferId == -1) {
         JsonObject error = new JsonObject();
         error.addProperty("error", "No SSBO found at index " + index);
         return error;
      }
      return scanBuffer(bufferId, index, DEFAULT_MAX_OFFSETS_TO_REPORT);
   }

   public static JsonObject scanBuffer(int bufferId, int index) {
      return scanBuffer(bufferId, index, DEFAULT_MAX_OFFSETS_TO_REPORT);
   }

   public static JsonObject scanBuffer(int bufferId, int index, int maxOffsetsToReport) {
      JsonObject result = new JsonObject();
      result.addProperty("index", index);
      result.addProperty("bufferId", bufferId);

      int previousBuffer = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
      ByteBuffer chunk = null;
      try {
         GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferId);

         int size = GL43C.glGetBufferParameteri(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_BUFFER_SIZE);
         result.addProperty("totalBytes", size);
         long totalFloats = size > 0 ? (long) size / Float.BYTES : 0L;
         result.addProperty("totalFloats", totalFloats);

         if (size <= 0) {
            result.addProperty("nanCount", 0L);
            result.addProperty("infiniteCount", 0L);
            result.addProperty("positiveInfCount", 0L);
            result.addProperty("negativeInfCount", 0L);
            result.addProperty("finiteCount", 0L);
            result.addProperty("firstNanByteOffset", -1L);
            result.addProperty("firstInfByteOffset", -1L);
            result.add("nanByteOffsets", new JsonArray());
            return result;
         }

         long nanCount = 0;
         long infiniteCount = 0;
         long positiveInfCount = 0;
         long negativeInfCount = 0;
         long finiteCount = 0;
         long firstNanByteOffset = -1;
         long firstInfByteOffset = -1;
         List<Long> nanByteOffsets = new ArrayList<>();

         int chunkSize = Math.min(size, SCAN_CHUNK_BYTES);
         chunk = MemoryUtil.memAlloc(chunkSize).order(ByteOrder.nativeOrder());

         for (int offset = 0; offset < size; offset += chunkSize) {
            int readLen = Math.min(chunkSize, size - offset);
            int floatCount = readLen / Float.BYTES;
            if (floatCount <= 0) break;

            GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offset, chunk);

            for (int i = 0; i < floatCount; i++) {
               long globalByteOffset = (long) offset + (long) i * Float.BYTES;
               int bits = chunk.getInt(i * Float.BYTES);
               int exp = (bits & 0x7F800000);
               int mantissa = (bits & 0x007FFFFF);
               boolean isNeg = ((bits & 0x80000000) != 0);

               if (exp == 0x7F800000) {
                  if (mantissa != 0) {
                     nanCount++;
                     if (firstNanByteOffset == -1) firstNanByteOffset = globalByteOffset;
                     if (nanByteOffsets.size() < maxOffsetsToReport) {
                        nanByteOffsets.add(globalByteOffset);
                     }
                  } else {
                     infiniteCount++;
                     if (firstInfByteOffset == -1) firstInfByteOffset = globalByteOffset;
                     if (isNeg) {
                        negativeInfCount++;
                     } else {
                        positiveInfCount++;
                     }
                  }
               } else {
                  finiteCount++;
               }
            }
         }

         result.addProperty("nanCount", nanCount);
         result.addProperty("infiniteCount", infiniteCount);
         result.addProperty("positiveInfCount", positiveInfCount);
         result.addProperty("negativeInfCount", negativeInfCount);
         result.addProperty("finiteCount", finiteCount);
         result.addProperty("firstNanByteOffset", firstNanByteOffset);
         result.addProperty("firstInfByteOffset", firstInfByteOffset);

         JsonArray offsetsArray = new JsonArray();
         for (Long off : nanByteOffsets) {
            offsetsArray.add(off);
         }
         result.add("nanByteOffsets", offsetsArray);
      } catch (Exception e) {
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         if (chunk != null) {
            MemoryUtil.memFree(chunk);
         }
         GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previousBuffer);
      }

      return result;
   }

   private static JsonObject preview(ByteBuffer data, int inspectedBytes) {
      JsonObject preview = new JsonObject();
      preview.addProperty("hex", hexPreview(data, inspectedBytes));
      preview.add("uint8", uint8Preview(data, inspectedBytes));

      int intCount = inspectedBytes / Integer.BYTES;
      int valueCount = Math.min(intCount, MAX_PREVIEW_VALUES);
      preview.add("int32", int32Preview(data, valueCount));
      preview.add("uint32", uint32Preview(data, valueCount));
      preview.add("float32", float32Preview(data, valueCount));
      preview.add("float32Stats", floatStats(data, intCount));
      return preview;
   }

   private static String hexPreview(ByteBuffer data, int inspectedBytes) {
      int count = Math.min(inspectedBytes, 128);
      StringBuilder builder = new StringBuilder(count * 3);
      for (int i = 0; i < count; i++) {
         if (i > 0) {
            builder.append(' ');
         }
         int value = Byte.toUnsignedInt(data.get(i));
         if (value < 0x10) {
            builder.append('0');
         }
         builder.append(Integer.toHexString(value));
      }
      return builder.toString();
   }

   private static JsonArray uint8Preview(ByteBuffer data, int inspectedBytes) {
      int count = Math.min(inspectedBytes, MAX_PREVIEW_VALUES);
      JsonArray values = new JsonArray();
      for (int i = 0; i < count; i++) {
         values.add(Byte.toUnsignedInt(data.get(i)));
      }
      return values;
   }

   private static JsonArray int32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         values.add(data.getInt(i * Integer.BYTES));
      }
      return values;
   }

   private static JsonArray uint32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         values.add(Integer.toUnsignedLong(data.getInt(i * Integer.BYTES)));
      }
      return values;
   }

   private static JsonArray float32Preview(ByteBuffer data, int valueCount) {
      JsonArray values = new JsonArray();
      for (int i = 0; i < valueCount; i++) {
         float value = data.getFloat(i * Float.BYTES);
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

   private static JsonObject floatStats(ByteBuffer data, int valueCount) {
      JsonObject stats = new JsonObject();
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      double sum = 0.0;
      int finiteCount = 0;
      int nanCount = 0;
      int infiniteCount = 0;

      for (int i = 0; i < valueCount; i++) {
         float value = data.getFloat(i * Float.BYTES);
         if (Float.isNaN(value)) {
            nanCount++;
         } else if (Float.isInfinite(value)) {
            infiniteCount++;
         } else {
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            finiteCount++;
         }
      }

      stats.addProperty("valuesRead", valueCount);
      stats.addProperty("finiteCount", finiteCount);
      stats.addProperty("nanCount", nanCount);
      stats.addProperty("infiniteCount", infiniteCount);
      if (finiteCount > 0) {
         stats.addProperty("min", min);
         stats.addProperty("max", max);
         stats.addProperty("avg", sum / finiteCount);
      }
      return stats;
   }
}
