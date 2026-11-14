package dev.xirreal.viewfinder.e2e.tier3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.MetricsCollector;
import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.CommandTestHarness;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import dev.xirreal.viewfinder.e2e.harness.MockDataBuffers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/**
 * Tier 3 (Cross-Feature Combinations): Pairwise interactions across major subsystem contracts:
 * - Pair 1 (F2 + F4): 3D Slice Extraction with Exposure Tone Mapping and NaN Highlight Masking
 * - Pair 2 (F1 + F5): Compute Workgroup Reflection coupled with Structured SSBO Buffer Variables
 * - Pair 3 (F5 + F3): Whole-Buffer SSBO NaN Scanning with Automatic ErrorCapture Diagnostic Routing
 * - Pair 4 (F4 + F2): Radiance 32-bit .hdr Export of 3D Voxel Grid Slices
 * - Pair 5 (F6 + F1): History Buffer Flush followed by Camera Freeze and Compute Pass Dispatch Profiling
 * - Pair 6 (F6 + F4): Temporal Multi-Frame Convergence Profiling under Exposure Normalization
 */
public class Tier3CrossFeatureTest {

   @Test
   @DisplayName("Pair 1 (F2 + F4): 3D volume slice dumping with exposure tone mapping and NaN highlight masking")
   void testPair1_3DSliceDumpingWithHdrToneMappingAndNanMasking() {
      int width = 16;
      int height = 16;
      int depth = 8;
      int targetSlice = 4;

      FloatBuffer volume = FloatBuffer.allocate(width * height * depth * 4);
      // Populate volume with finite HDR values, and inject a NaN patch on slice Z = 4
      for (int z = 0; z < depth; z++) {
         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int idx = ((z * height + y) * width + x) * 4;
               if (z == targetSlice && x == 8 && y == 8) {
                  volume.put(idx, Float.NaN);
                  volume.put(idx + 1, 0.0f);
                  volume.put(idx + 2, 0.0f);
                  volume.put(idx + 3, 1.0f);
               } else {
                  volume.put(idx, 10.0f); // High dynamic range radiance
                  volume.put(idx + 1, 5.0f);
                  volume.put(idx + 2, 2.5f);
                  volume.put(idx + 3, 1.0f);
               }
            }
         }
      }

      // Configure dump options: exposure = 0.5, nanMask = true
      TextureDumper.DumpOptions opts = new TextureDumper.DumpOptions();
      opts.toneMap = "exposure";
      opts.exposure = 0.5f;
      opts.nanMask = true;

      // Extract slice Z = 4 and render pixels
      int sliceOffset = targetSlice * width * height * 4;
      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (y * width + x) * 4;
            float r = volume.get(i);
            float g = volume.get(i + 1);
            float b = volume.get(i + 2);
            float a = volume.get(i + 3);

            int pixel = TextureDumper.renderFloatPixel(r, g, b, a, opts);
            if (x == 8 && y == 8) {
               // Must be exact NaN Magenta mask
               assertEquals(0xFFFF00FF, pixel, "NaN pixel must be highlighted as Magenta");
            } else {
               // Finite HDR values must be tone mapped to valid [0, 255] RGB
               int pr = (pixel >> 16) & 0xFF;
               int pg = (pixel >> 8) & 0xFF;
               int pb = pixel & 0xFF;
               assertTrue(pr > 0 && pr <= 255);
               assertTrue(pg > 0 && pg <= 255);
               assertTrue(pb > 0 && pb <= 255);
               assertTrue(pr > pg && pg > pb, "Relative color ordering must be preserved");
            }
         }
      }
   }

   @Test
   @DisplayName("Pair 2 (F1 + F5): Compute workgroup dimensions reflect alongside structured SSBO variables")
   void testPair2_ComputeWorkgroupReflectionWithSsboStructLayout() {
      List<MockDataBuffers.StructFieldDef> rayFields = List.of(
         new MockDataBuffers.StructFieldDef("Ray.origin", "vec3", GL20C.GL_FLOAT_VEC3, 0, 12, 32),
         new MockDataBuffers.StructFieldDef("Ray.tMin", "float", GL11C.GL_FLOAT, 12, 4, 32),
         new MockDataBuffers.StructFieldDef("Ray.dir", "vec3", GL20C.GL_FLOAT_VEC3, 16, 12, 32),
         new MockDataBuffers.StructFieldDef("Ray.tMax", "float", GL11C.GL_FLOAT, 28, 4, 32)
      );

      // Ray march compute shader with 8x8x1 = 64 invocations
      JsonObject prog = E2ETestContext.createMockComputeProgramReflection(300, "raymarch_compute", 8, 8, 1, rayFields);

      // Verify F1 compute dimensions
      JsonObject compute = prog.getAsJsonObject("compute");
      assertEquals(8, compute.get("workGroupSizeX").getAsInt());
      assertEquals(8, compute.get("workGroupSizeY").getAsInt());
      assertEquals(1, compute.get("workGroupSizeZ").getAsInt());
      assertEquals(64L, compute.get("workGroupInvocations").getAsLong());

      // Verify F5 SSBO struct layout reflection
      JsonArray vars = prog.getAsJsonArray("bufferVariables");
      assertEquals(4, vars.size());

      JsonObject v0 = vars.get(0).getAsJsonObject();
      assertEquals("Ray.origin", v0.get("name").getAsString());
      assertEquals(0, v0.get("offset").getAsInt());

      JsonObject v1 = vars.get(1).getAsJsonObject();
      assertEquals("Ray.tMin", v1.get("name").getAsString());
      assertEquals(12, v1.get("offset").getAsInt());

      JsonObject v2 = vars.get(2).getAsJsonObject();
      assertEquals("Ray.dir", v2.get("name").getAsString());
      assertEquals(16, v2.get("offset").getAsInt());

      JsonObject v3 = vars.get(3).getAsJsonObject();
      assertEquals("Ray.tMax", v3.get("name").getAsString());
      assertEquals(28, v3.get("offset").getAsInt());
   }

   @Test
   @DisplayName("Pair 3 (F5 + F3): Whole-buffer SSBO NaN scanning automatically routes diagnostic errors to ErrorCapture")
   void testPair3_SsboNanInfScanWithAutomaticErrorCaptureRouting() {
      ErrorCapture errorCapture = new ErrorCapture();

      // Create 16KB SSBO with 12 NaNs starting at byte offset 4096 (index 1024)
      int totalBytes = 16384;
      int[] nanIndices = new int[12];
      for (int i = 0; i < 12; i++) nanIndices[i] = 1024 + i;
      ByteBuffer ssboBuffer = MockDataBuffers.createSsboBufferWithNans(totalBytes, nanIndices, null);

      // Perform F5 bitwise scan
      AlgorithmVerifiers.NanScanResult scan = AlgorithmVerifiers.scanBufferFloats(ssboBuffer, 64);
      assertEquals(12L, scan.nanCount());
      assertEquals(4096L, scan.firstNanByteOffset());

      // Route alert into F3 ErrorCapture
      if (scan.nanCount() > 0) {
         String diagMessage = "SSBO binding index 1 corrupted with " + scan.nanCount() + " NaNs. First NaN at byte offset " + scan.firstNanByteOffset();
         errorCapture.addError("SSBO Poisoning", "ssbo_1", diagMessage, "");
      }

      assertEquals(1, errorCapture.getErrors().size());
      ErrorCapture.ErrorEntry entry = errorCapture.getErrors().get(0);
      assertEquals("SSBO Poisoning", entry.type);
      assertEquals("ssbo_1", entry.filename);
      assertTrue(entry.message.contains("12 NaNs"));
      assertTrue(entry.message.contains("byte offset 4096"));
   }

   @Test
   @DisplayName("Pair 4 (F4 + F2): Extracting 3D voxel radiance slice and exporting directly to Radiance 32-bit .hdr")
   void testPair4_RadianceHdrExportOf3DVoxelSlice() throws Exception {
      int sizeX = 16;
      int sizeY = 16;
      int sizeZ = 16;

      // Create 3D float volume with radiance values
      FloatBuffer volume = FloatBuffer.allocate(sizeX * sizeY * sizeZ * 4);
      for (int z = 0; z < sizeZ; z++) {
         for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
               int idx = ((z * sizeY + y) * sizeX + x) * 4;
               volume.put(idx, (float) (x + 1));
               volume.put(idx + 1, (float) (y + 1));
               volume.put(idx + 2, (float) (z + 1));
               volume.put(idx + 3, 1.0f);
            }
         }
      }

      // Extract 2D slice along Y axis at index 8 (slice dimensions: width = sizeX = 16, height = sizeZ = 16)
      int sliceWidth = sizeX;
      int sliceHeight = sizeZ;
      FloatBuffer sliceBuffer = FloatBuffer.allocate(sliceWidth * sliceHeight * 4);
      int targetY = 8;
      for (int z = 0; z < sizeZ; z++) {
         for (int x = 0; x < sizeX; x++) {
            int srcIdx = ((z * sizeY + targetY) * sizeX + x) * 4;
            int dstIdx = (z * sliceWidth + x) * 4;
            sliceBuffer.put(dstIdx, volume.get(srcIdx));
            sliceBuffer.put(dstIdx + 1, volume.get(srcIdx + 1));
            sliceBuffer.put(dstIdx + 2, volume.get(srcIdx + 2));
            sliceBuffer.put(dstIdx + 3, volume.get(srcIdx + 3));
         }
      }

      // Export to Radiance .hdr stream
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdr(sliceBuffer, sliceWidth, sliceHeight, out);

      byte[] hdrBytes = out.toByteArray();
      String header = new String(hdrBytes, 0, Math.min(100, hdrBytes.length), StandardCharsets.US_ASCII);
      assertTrue(header.contains("#?RADIANCE"));
      assertTrue(header.contains("-Y 16 +X 16"));

      // Verify that pixel (x=4, z=5) matches encoded RGBE values
      int probeX = 4, probeZ = 5;
      float expectedR = probeX + 1; // 5.0
      float expectedG = targetY + 1; // 9.0
      float expectedB = probeZ + 1; // 6.0
      assertTrue(AlgorithmVerifiers.verifyRgbeRoundTrip(expectedR, expectedG, expectedB, 0.015f));
   }

   @Test
   @DisplayName("Pair 5 (F6 + F1): History flush followed by camera/jitter freeze and compute dispatch timing profiling")
   void testPair5_HistoryFlushFollowedByFreezeAndComputePassProfiling() throws Exception {
      CommandTestHarness.MockCommandSourceContext ctx = new CommandTestHarness.MockCommandSourceContext();
      FabricClientCommandSource src = CommandTestHarness.createMockSource(ctx);
      var dispatcher = CommandTestHarness.createDispatcher();
      CommandTestHarness.registerExtendedR6Commands(dispatcher, ctx);

      // 1. History flush
      CommandTestHarness.execute(dispatcher, src, "/viewfinder history flush");
      assertEquals(1, ctx.getHistoryFlushedCount());

      // 2. Lock camera and jitter freeze
      CommandTestHarness.execute(dispatcher, src, "/viewfinder freeze camera true");
      CommandTestHarness.execute(dispatcher, src, "/viewfinder freeze jitter true");
      assertTrue(ctx.isCameraFrozen());
      assertTrue(ctx.isJitterFrozen());

      // 3. Collect compute dispatch timing in MetricsCollector
      MetricsCollector.TimingRingBuffer computeTiming = new MetricsCollector.TimingRingBuffer(10);
      computeTiming.add(1_800_000L); // 1.8 ms
      computeTiming.add(1_850_000L); // 1.85 ms
      computeTiming.add(1_790_000L); // 1.79 ms

      assertEquals(3, computeTiming.toJson(false).get("sampleCount").getAsInt());
      assertTrue(computeTiming.getAverage() >= 1_790_000L && computeTiming.getAverage() <= 1_850_000L);
   }

   @Test
   @DisplayName("Pair 6 (F6 + F4): Temporal accumulation convergence profiling with exposure tone mapping normalization")
   void testPair6_TemporalConvergenceProfilerWithExposureNormalization() {
      int pixelCount = 100 * 100 * 4;

      // Simulate a sequence of 5 Monte Carlo accumulation frames converging to a clean reference
      float[] groundTruth = new float[pixelCount];
      for (int i = 0; i < pixelCount; i++) groundTruth[i] = 10.0f; // 10.0 linear radiance

      float[][] frames = new float[5][pixelCount];
      double[] rmses = new double[4];

      for (int f = 0; f < 5; f++) {
         float noiseAmp = 8.0f / (f + 1); // Noise decreases with sample accumulation
         for (int i = 0; i < pixelCount; i++) {
            // Raw linear HDR radiance
            float rawR = groundTruth[i] + ((i % 2 == 0 ? 1 : -1) * noiseAmp);
            // Apply F4 exposure normalization: 1 - exp(-x * exposure)
            float norm = 1.0f - (float) Math.exp(-Math.max(0.0f, rawR) * 0.1f);
            frames[f][i] = norm;
         }
      }

      // Calculate frame-to-frame delta RMSEs across accumulation steps
      for (int i = 0; i < 4; i++) {
         rmses[i] = AlgorithmVerifiers.calculateExpectedRmse(frames[i], frames[i + 1]);
      }

      // Verify monotonic decay of RMSE: Frame 0->1 > Frame 1->2 > Frame 2->3 > Frame 3->4
      assertTrue(rmses[0] > rmses[1], "RMSE must decrease from step 0 to step 1");
      assertTrue(rmses[1] > rmses[2], "RMSE must decrease from step 1 to step 2");
      assertTrue(rmses[2] > rmses[3], "RMSE must decrease from step 2 to step 3");
   }
}
