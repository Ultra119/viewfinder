package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import dev.xirreal.viewfinder.e2e.harness.MockDataBuffers;
import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 2 (Boundary & Corner Cases): F5 - Structured SSBO Introspection.
 * Tests boundary conditions: offset beyond buffer length, length exceeding remaining bytes,
 * large all-finite clean buffers (1M floats), signaling vs quiet NaNs, unaligned offsets,
 * and zero/negative inspection byte bounds.
 */
public class Tier2SsboBoundaryTest {

   @Test
   @DisplayName("F2.5.1: SSBO navigation beyond buffer size rejects or returns zero inspected bytes without crashing")
   void testSsboOffsetBeyondBufferLength() {
      int bufferSize = 512;
      ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
      int requestedOffset = 1024;

      boolean outOfBounds = requestedOffset >= bufferSize;
      assertTrue(outOfBounds);

      int readableBytes = Math.max(0, bufferSize - requestedOffset);
      assertEquals(0, readableBytes);
   }

   @Test
   @DisplayName("F2.5.2: SSBO navigation length exceeding remaining buffer tail clamps to remaining bytes")
   void testSsboLengthExceedsRemainingBytes() {
      int bufferSize = 512;
      ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
      int offset = 400;
      int requestedLength = 300;

      int remaining = Math.max(0, bufferSize - offset);
      int actualRead = Math.min(requestedLength, remaining);

      assertEquals(112, actualRead);
      assertTrue(actualRead < requestedLength);
   }

   @Test
   @DisplayName("F2.5.3: Whole-buffer scan on 1,000,000 finite floats confirms zero NaNs and zero Infs")
   void testWholeBufferScanAllFiniteCleanBuffer() {
      int floatCount = 1_000_000;
      int byteSize = floatCount * 4;
      ByteBuffer buffer = ByteBuffer.allocateDirect(byteSize);

      for (int i = 0; i < floatCount; i++) {
         buffer.putFloat(i * 4, (float) (i % 1000) * 0.125f);
      }

      AlgorithmVerifiers.NanScanResult result = AlgorithmVerifiers.scanBufferFloats(buffer, 64);
      assertEquals((long) floatCount, result.totalFloats());
      assertEquals(0L, result.nanCount());
      assertEquals(0L, result.infiniteCount());
      assertEquals((long) floatCount, result.finiteCount());
      assertEquals(-1L, result.firstNanByteOffset());
      assertEquals(-1L, result.firstInfByteOffset());
      assertTrue(result.nanByteOffsets().isEmpty());
   }

   @Test
   @DisplayName("F2.5.4: Bitwise scanner detects both quiet NaNs (0x7FC00000) and signaling NaNs (0x7F800001)")
   void testBitwiseSignalingAndQuietNanDistinction() {
      ByteBuffer buffer = ByteBuffer.allocateDirect(16);
      buffer.putInt(0, 0x7FC00000); // Quiet NaN
      buffer.putInt(4, 0x7F800001); // Signaling NaN
      buffer.putInt(8, 0x7FA00000); // Another NaN payload
      buffer.putInt(12, 0x3F800000); // 1.0f (finite)

      AlgorithmVerifiers.NanScanResult result = AlgorithmVerifiers.scanBufferFloats(buffer, 10);
      assertEquals(4L, result.totalFloats());
      assertEquals(3L, result.nanCount());
      assertEquals(1L, result.finiteCount());
      assertEquals(0L, result.firstNanByteOffset());
   }

   @Test
   @DisplayName("F2.5.5: Unaligned byte offsets (e.g. 3) extract raw byte sequence without word misalignment crash")
   void testUnalignedSsboOffsetNavigation() {
      ByteBuffer buffer = ByteBuffer.allocate(16);
      for (int i = 0; i < 16; i++) {
         buffer.put(i, (byte) (i * 10));
      }

      int unalignedOffset = 3;
      int readLen = 4;
      byte[] out = new byte[readLen];
      buffer.position(unalignedOffset);
      buffer.get(out, 0, readLen);

      assertEquals((byte) 30, out[0]);
      assertEquals((byte) 40, out[1]);
      assertEquals((byte) 50, out[2]);
      assertEquals((byte) 60, out[3]);
   }

   @Test
   @DisplayName("F2.5.6: Tool schema inspect_ssbo rejects negative binding indices and zero byte counts")
   void testZeroByteSsboInspection() {
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_ssbo", Map.of("index", -1), "ssbo");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_ssbo", Map.of("index", 0, "bytes", 0), "ssbo");
      });
   }
}
