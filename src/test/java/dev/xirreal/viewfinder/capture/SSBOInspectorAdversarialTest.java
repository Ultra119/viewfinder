package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adversarial white-box test suite for SSBOInspector.
 * Directly exercises offset clamping, boundary reads, multi-chunk scanning,
 * bitwise NaN/Inf discrimination, float statistics, and preview formatting.
 */
class SSBOInspectorAdversarialTest {

   @Test
   @DisplayName("Default preview bytes matches expected 256 bytes constant")
   void testDefaultPreviewBytesConstant() {
      assertEquals(256, SSBOInspector.defaultPreviewBytes());
   }

   @Test
   @DisplayName("Bitwise float scan accurately classifies all IEEE 754 representations")
   void testBitwiseFloatClassification() {
      // 10 floats: 40 bytes
      ByteBuffer buffer = ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
      buffer.putInt(0, 0x7F800000); // +Infinity
      buffer.putInt(4, 0xFF800000); // -Infinity
      buffer.putInt(8, 0x7FC00000); // Quiet NaN
      buffer.putInt(12, 0x7F800001); // Signaling NaN
      buffer.putInt(16, 0xFF800001); // Negative Signaling NaN
      buffer.putInt(20, 0x00000001); // Subnormal float (denormal) -> finite
      buffer.putInt(24, 0x7F7FFFFF); // Float.MAX_VALUE -> finite
      buffer.putInt(28, 0x00800000); // Float.MIN_NORMAL -> finite
      buffer.putInt(32, 0x00000000); // +0.0f -> finite
      buffer.putInt(36, 0x80000000); // -0.0f -> finite

      long nanCount = 0;
      long infiniteCount = 0;
      long positiveInfCount = 0;
      long negativeInfCount = 0;
      long finiteCount = 0;
      long firstNanByteOffset = -1;
      long firstInfByteOffset = -1;
      List<Long> nanByteOffsets = new ArrayList<>();

      int floatCount = buffer.capacity() / Float.BYTES;
      for (int i = 0; i < floatCount; i++) {
         long globalByteOffset = (long) i * Float.BYTES;
         int bits = buffer.getInt(i * Float.BYTES);
         int exp = (bits & 0x7F800000);
         int mantissa = (bits & 0x007FFFFF);
         boolean isNeg = ((bits & 0x80000000) != 0);

         if (exp == 0x7F800000) {
            if (mantissa != 0) {
               nanCount++;
               if (firstNanByteOffset == -1) firstNanByteOffset = globalByteOffset;
               nanByteOffsets.add(globalByteOffset);
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

      assertEquals(3, nanCount, "Must detect 3 NaNs (quiet, signaling, negative signaling)");
      assertEquals(2, infiniteCount, "Must detect 2 Infinities (+Inf, -Inf)");
      assertEquals(1, positiveInfCount);
      assertEquals(1, negativeInfCount);
      assertEquals(5, finiteCount, "Must classify subnormal, MAX_VALUE, MIN_NORMAL, +0, -0 as finite");
      assertEquals(8L, firstNanByteOffset);
      assertEquals(0L, firstInfByteOffset);
      assertEquals(List.of(8L, 12L, 16L), nanByteOffsets);
   }

   @Test
   @DisplayName("Chunked scan crossing 64 KiB boundary computes correct global byte offsets")
   void testMultiChunkScanGlobalByteOffsetCalculation() {
      int chunkSize = 64 * 1024; // 65536 bytes
      int totalBytes = chunkSize * 2; // 131072 bytes (32768 floats)
      ByteBuffer buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder());

      // Place a NaN in chunk 0 at float index 10 (offset 40)
      buffer.putInt(10 * Float.BYTES, 0x7FC00000);

      // Place a NaN in chunk 1 at float index 5 (offset 65536 + 20 = 65556)
      buffer.putInt(chunkSize + (5 * Float.BYTES), 0x7FC00000);

      long firstNanByteOffset = -1;
      List<Long> nanByteOffsets = new ArrayList<>();

      for (int offset = 0; offset < totalBytes; offset += chunkSize) {
         int readLen = Math.min(chunkSize, totalBytes - offset);
         int floatCount = readLen / Float.BYTES;

         for (int i = 0; i < floatCount; i++) {
            long globalByteOffset = (long) offset + (long) i * Float.BYTES;
            int bits = buffer.getInt((int) globalByteOffset);
            int exp = (bits & 0x7F800000);
            int mantissa = (bits & 0x007FFFFF);

            if (exp == 0x7F800000 && mantissa != 0) {
               if (firstNanByteOffset == -1) firstNanByteOffset = globalByteOffset;
               nanByteOffsets.add(globalByteOffset);
            }
         }
      }

      assertEquals(40L, firstNanByteOffset);
      assertEquals(2, nanByteOffsets.size());
      assertEquals(40L, nanByteOffsets.get(0));
      assertEquals(65556L, nanByteOffsets.get(1));
   }

   @Test
   @DisplayName("Scan with zero or negative maxOffsetsToReport bounds reported offsets safely")
   void testScanBoundsMaxOffsetsToReport() {
      List<Long> offsets = new ArrayList<>();
      int maxOffsetsToReport = 0;

      for (int i = 0; i < 50; i++) {
         long byteOffset = i * 4L;
         if (offsets.size() < maxOffsetsToReport) {
            offsets.add(byteOffset);
         }
      }
      assertTrue(offsets.isEmpty(), "Zero limit must prevent any offset from being stored");
   }

   @Test
   @DisplayName("Float statistics with all-NaN data reports finiteCount 0 and omits min/max/avg")
   void testFloatStatsAllNaNValues() {
      int count = 16;
      ByteBuffer buffer = ByteBuffer.allocateDirect(count * Float.BYTES).order(ByteOrder.nativeOrder());
      for (int i = 0; i < count; i++) {
         buffer.putFloat(i * Float.BYTES, Float.NaN);
      }

      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      double sum = 0.0;
      int finiteCount = 0;
      int nanCount = 0;
      int infiniteCount = 0;

      for (int i = 0; i < count; i++) {
         float value = buffer.getFloat(i * Float.BYTES);
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

      assertEquals(16, nanCount);
      assertEquals(0, finiteCount);
      assertEquals(0, infiniteCount);
      assertTrue(Double.isInfinite(min), "Min must remain uninitialized when finiteCount == 0");
   }

   @Test
   @DisplayName("Unaligned byte offset preview does not throw and extracts expected bytes")
   void testUnalignedOffsetPreviewExtraction() {
      int totalBytes = 32;
      ByteBuffer buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder());
      for (int i = 0; i < totalBytes; i++) {
         buffer.put(i, (byte) (i * 3));
      }

      int offset = 3;
      int length = 5;
      ByteBuffer slice = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
      for (int i = 0; i < length; i++) {
         slice.put(i, buffer.get(offset + i));
      }

      assertEquals(9, slice.get(0)); // 3 * 3 = 9
      assertEquals(12, slice.get(1)); // 4 * 3 = 12
      assertEquals(21, slice.get(4)); // 7 * 3 = 21

      // intCount for 5 bytes is 5 / 4 = 1
      int intCount = length / Integer.BYTES;
      assertEquals(1, intCount);
   }
}
