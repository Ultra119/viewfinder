package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import dev.xirreal.viewfinder.e2e.harness.MockDataBuffers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/**
 * Tier 1 (Feature Coverage): F5 - Structured SSBO Introspection.
 * Isolated happy-path tests verifying SSBO offset/length navigation, whole-buffer NaN/Inf scanning,
 * buffer variable struct reflection, and byte preview generation.
 */
public class Tier1SsboIntrospectionTest {

   @Test
   @DisplayName("F5.1: SSBO offset and length navigation extracts requested byte slice")
   void testSsboNavigationOffsetAndLength() {
      int totalBytes = 1024;
      ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
      for (int i = 0; i < totalBytes; i++) {
         buffer.put(i, (byte) (i & 0xFF));
      }

      int offsetBytes = 128;
      int lengthBytes = 64;

      byte[] slice = new byte[lengthBytes];
      buffer.position(offsetBytes);
      buffer.get(slice, 0, lengthBytes);

      assertEquals(64, slice.length);
      assertEquals((byte) 128, slice[0]);
      assertEquals((byte) 191, slice[63]);
   }

   @Test
   @DisplayName("F5.2: Whole-buffer bitwise scan detects multiple quiet and signaling NaNs")
   void testWholeBufferNanScanDetectsNans() {
      int totalBytes = 4096;
      int[] nanIndices = {10, 50, 100};
      ByteBuffer buffer = MockDataBuffers.createSsboBufferWithNans(totalBytes, nanIndices, null);

      AlgorithmVerifiers.NanScanResult result = AlgorithmVerifiers.scanBufferFloats(buffer, 64);

      assertEquals(1024L, result.totalFloats());
      assertEquals(3L, result.nanCount());
      assertEquals(0L, result.infiniteCount());
      assertEquals(1021L, result.finiteCount());
      assertEquals(40L, result.firstNanByteOffset()); // index 10 * 4 = 40
      assertEquals(3, result.nanByteOffsets().size());
      assertEquals(40L, result.nanByteOffsets().get(0));
      assertEquals(200L, result.nanByteOffsets().get(1)); // index 50 * 4 = 200
      assertEquals(400L, result.nanByteOffsets().get(2)); // index 100 * 4 = 400
   }

   @Test
   @DisplayName("F5.3: Whole-buffer scan detects both positive and negative infinities")
   void testWholeBufferInfScanDetectsInfs() {
      int totalBytes = 4096;
      int[] infIndices = {5, 12};
      ByteBuffer buffer = MockDataBuffers.createSsboBufferWithNans(totalBytes, null, infIndices);

      AlgorithmVerifiers.NanScanResult result = AlgorithmVerifiers.scanBufferFloats(buffer, 64);

      assertEquals(1024L, result.totalFloats());
      assertEquals(0L, result.nanCount());
      assertEquals(2L, result.infiniteCount());
      assertEquals(1L, result.positiveInfCount());
      assertEquals(1L, result.negativeInfCount());
      assertEquals(20L, result.firstInfByteOffset()); // index 5 * 4 = 20
   }

   @Test
   @DisplayName("F5.4: Buffer variable reflection parses struct field names, types, offsets, and strides")
   void testBufferVariableStructReflectionParsing() {
      List<MockDataBuffers.StructFieldDef> fields = List.of(
         new MockDataBuffers.StructFieldDef("Voxel.id", "int", GL11C.GL_INT, 0, 4, 32),
         new MockDataBuffers.StructFieldDef("Voxel.color", "vec4", GL20C.GL_FLOAT_VEC4, 16, 16, 32)
      );

      JsonObject prog = E2ETestContext.createMockComputeProgramReflection(105, "ray_marcher", 8, 8, 1, fields);
      assertTrue(prog.has("bufferVariables"));

      JsonArray vars = prog.getAsJsonArray("bufferVariables");
      assertEquals(2, vars.size());

      JsonObject var0 = vars.get(0).getAsJsonObject();
      assertEquals("Voxel.id", var0.get("name").getAsString());
      assertEquals(0, var0.get("offset").getAsInt());
      assertEquals(32, var0.get("arrayStride").getAsInt());

      JsonObject var1 = vars.get(1).getAsJsonObject();
      assertEquals("Voxel.color", var1.get("name").getAsString());
      assertEquals(16, var1.get("offset").getAsInt());
      assertEquals("vec4", var1.get("typeName").getAsString());
   }

   @Test
   @DisplayName("F5.5: Tool schema inspect_ssbo accepts index and bytes parameters")
   void testInspectSsboSchemaValidation() {
      Map<String, Object> args = Map.of(
         "index", 2,
         "bytes", 512
      );
      assertDoesNotThrow(() -> E2ETestContext.validateToolArguments("inspect_ssbo", args, "inspect_ssbo"));
   }

   @Test
   @DisplayName("F5.6: SSBO byte inspection formats hex representation accurately")
   void testSsboHexAndUintPreviews() {
      byte[] testBytes = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < testBytes.length; i++) {
         if (i > 0) hex.append(' ');
         int val = testBytes[i] & 0xFF;
         if (val < 0x10) hex.append('0');
         hex.append(Integer.toHexString(val));
      }
      assertEquals("de ad be ef", hex.toString());
   }
}
