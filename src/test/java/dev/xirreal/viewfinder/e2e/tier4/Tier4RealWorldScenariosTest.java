package dev.xirreal.viewfinder.e2e.tier4;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/**
 * Tier 4 (Real-World Application Scenarios): Realistic multi-step developer workflows:
 * - Scenario 1: Voxel Grid Path Tracer Debugging Workflow (F1 + F2 + F4)
 * - Scenario 2: Temporal Denoising Accumulation & Convergence Workflow (F6 + F4 + F1)
 * - Scenario 3: Driver Hardware Boundary & Compute Profiling Workflow (F3 + F1 + F5)
 * - Scenario 4: SSBO Layout Reflection & Bitwise NaN Poisoning Isolation (F5 + F3 + F2)
 * - Scenario 5: Automated Headless Diagnostics Batch Workflow (MCP run_actions End-to-End)
 */
public class Tier4RealWorldScenariosTest {

   @Test
   @DisplayName("Scenario 1: Voxel Grid Path Tracer Debugging (Reflection -> Diagnostics -> Slicing -> Heatmap)")
   void testScenario1_VoxelGridPathTracerDebuggingWorkflow() {
      // Step 1: Inspect voxel builder compute program
      JsonObject computeProg = E2ETestContext.createMockComputeProgramReflection(150, "voxel_builder", 8, 8, 8, null);
      JsonObject compute = computeProg.getAsJsonObject("compute");
      assertEquals(512L, compute.get("workGroupInvocations").getAsLong());

      // Step 2: Query 3D voxel grid diagnostics on 32x32x32 voxel texture
      int dim = 32;
      Map<String, Integer> sceneVoxels = new HashMap<>();
      // Simulate stone floor at y = 0
      for (int x = 0; x < dim; x++) {
         for (int z = 0; z < dim; z++) {
            sceneVoxels.put(x + ",0," + z, 1); // Stone ID 1
         }
      }
      // Simulate a stone pillar from y = 1 to y = 15 at x = 16, z = 16
      for (int y = 1; y <= 15; y++) {
         sceneVoxels.put("16," + y + ",16", 4); // Cobblestone ID 4
      }

      IntBuffer voxelGrid = MockDataBuffers.createVoxelGrid(dim, dim, dim, sceneVoxels);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(voxelGrid, dim, dim, dim, 1);

      long expectedOccupied = (32 * 32) + 15; // 1024 + 15 = 1039
      assertEquals(expectedOccupied, diag.occupiedVoxels());
      assertTrue(diag.occupancyRatio() > 0.03 && diag.occupancyRatio() < 0.05);
      assertEquals(0, diag.minBoundingBox()[1]); // floor at y = 0
      assertEquals(15, diag.maxBoundingBox()[1]); // pillar top at y = 15

      // Step 3: Verify orthogonal 2D slice dumping with golden ratio hash palette
      int stoneColor = TextureDumper.voxelIdToArgb(1);
      int pillarColor = TextureDumper.voxelIdToArgb(4);
      int airColor = TextureDumper.voxelIdToArgb(0);

      assertEquals(0x00000000, airColor);
      assertTrue(stoneColor != 0 && pillarColor != 0 && stoneColor != pillarColor);

      // Step 4: Capture path tracing ray march iteration count with Turbo heatmap
      float maxRaySteps = 64.0f;
      int floorHeatmap = TextureDumper.sampleHeatmap("turbo", 4.0f, maxRaySteps); // floor hit quickly
      int pillarHeatmap = TextureDumper.sampleHeatmap("turbo", 16.0f, maxRaySteps); // pillar hit mid-way
      int missHeatmap = TextureDumper.sampleHeatmap("turbo", 64.0f, maxRaySteps); // sky miss max steps

      assertTrue(floorHeatmap != pillarHeatmap);
      assertTrue(pillarHeatmap != missHeatmap);
      assertEquals(0xFF, (floorHeatmap >> 24) & 0xFF);
   }

   @Test
   @DisplayName("Scenario 2: Temporal Denoising Accumulation & Convergence (Freeze -> Flush -> Multi-Frame Profiler -> 32-bit HDR)")
   void testScenario2_TemporalDenoisingAccumulationConvergenceWorkflow() throws Exception {
      CommandTestHarness.MockCommandSourceContext ctx = new CommandTestHarness.MockCommandSourceContext();
      FabricClientCommandSource src = CommandTestHarness.createMockSource(ctx);
      var dispatcher = CommandTestHarness.createDispatcher();
      CommandTestHarness.registerExtendedR6Commands(dispatcher, ctx);

      // Step 1: Lock camera view matrix and temporal jitter
      CommandTestHarness.execute(dispatcher, src, "/viewfinder freeze camera true");
      CommandTestHarness.execute(dispatcher, src, "/viewfinder freeze jitter true");
      assertTrue(ctx.isCameraFrozen());
      assertTrue(ctx.isJitterFrozen());

      // Step 2: Flush history textures to reset ghosting
      CommandTestHarness.execute(dispatcher, src, "/viewfinder history flush");
      assertEquals(1, ctx.getHistoryFlushedCount());

      // Step 3: Run convergence profiler over 30 accumulation frames
      int pixelCount = 64 * 64 * 4;
      float[] baseTruth = new float[pixelCount];
      for (int i = 0; i < pixelCount; i++) baseTruth[i] = 25.0f; // High dynamic range radiance

      float[] prevFrame = new float[pixelCount];
      float[] currFrame = new float[pixelCount];
      double initialRmse = 0.0;
      double finalRmse = 0.0;

      for (int frame = 1; frame <= 30; frame++) {
         float noiseScale = 10.0f / (float) Math.sqrt(frame);
         for (int i = 0; i < pixelCount; i++) {
            currFrame[i] = baseTruth[i] + ((i % 3 == 0 ? 1 : -1) * noiseScale);
         }
         if (frame > 1) {
            double deltaRmse = AlgorithmVerifiers.calculateExpectedRmse(prevFrame, currFrame);
            if (frame == 2) initialRmse = deltaRmse;
            if (frame == 30) finalRmse = deltaRmse;
         }
         System.arraycopy(currFrame, 0, prevFrame, 0, pixelCount);
      }

      assertTrue(initialRmse > finalRmse, "Convergence error must decrease over 30 accumulation frames");
      assertTrue(finalRmse < 1.0, "Converged frame delta must be low");

      // Step 4: Dump converged frame directly to Radiance 32-bit .hdr
      FloatBuffer convergedBuffer = FloatBuffer.wrap(currFrame);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdr(convergedBuffer, 64, 64, out);

      byte[] hdrOutput = out.toByteArray();
      String header = new String(hdrOutput, 0, 100, StandardCharsets.US_ASCII);
      assertTrue(header.startsWith("#?RADIANCE"));
      assertTrue(header.contains("-Y 64 +X 64"));
   }

   @Test
   @DisplayName("Scenario 3: Driver Hardware Boundary & Compute Profiling (Hardware Limits -> Timing Dispatch -> KHR_Debug Interception)")
   void testScenario3_DriverCrashInterceptionAndComputeProfilingWorkflow() {
      // Step 1: Query driver hardware limits
      JsonObject driver = E2ETestContext.createMockDriverInfo("NVIDIA Corporation", "GeForce RTX 3080", "4.6.0 NVIDIA 535", 1024, 49152, 2147483648L);
      int maxInvocations = driver.get("maxComputeWorkGroupInvocations").getAsInt();
      long maxSsboBytes = driver.get("maxShaderStorageBlockSize").getAsLong();
      assertEquals(1024, maxInvocations);
      assertEquals(2147483648L, maxSsboBytes);

      // Step 2: Validate compute dispatch near hardware boundary
      JsonObject nearLimitCompute = E2ETestContext.createMockComputeProgramReflection(400, "denoise_compute", 32, 32, 1, null);
      long invocations = nearLimitCompute.getAsJsonObject("compute").get("workGroupInvocations").getAsLong();
      assertEquals(1024L, invocations);
      assertTrue(invocations <= maxInvocations);

      // Step 3: Collect GPU dispatch timing samples
      MetricsCollector.TimingRingBuffer timer = new MetricsCollector.TimingRingBuffer(50);
      timer.add(1_450_000L); // 1.45 ms
      timer.add(1_480_000L); // 1.48 ms
      timer.add(1_420_000L); // 1.42 ms
      assertEquals(3, timer.toJson(false).get("sampleCount").getAsInt());
      assertTrue(timer.getAverage() > 1_400_000L && timer.getAverage() < 1_500_000L);

      // Step 4: Intercept driver KHR_debug error in ErrorCapture
      ErrorCapture errors = new ErrorCapture();
      errors.addError("OpenGL Error", "denoise_compute", "GL_OUT_OF_MEMORY during texture allocation", "at glDispatchCompute(Native Method)");

      assertEquals(1, errors.getErrors().size());
      ErrorCapture.ErrorEntry err = errors.getErrors().get(0);
      assertEquals("OpenGL Error", err.type);
      assertEquals("denoise_compute", err.filename);
      assertTrue(err.message.contains("GL_OUT_OF_MEMORY"));
   }

   @Test
   @DisplayName("Scenario 4: SSBO Structure Layout & Bitwise NaN Poisoning Isolation (Reflection -> NaN Scan -> Variable Mapping -> Error Capture)")
   void testScenario4_SsboStructureLayoutAndBitwiseNanPoisoningIsolation() {
      // Step 1: Reflect SSBO struct definition for RadianceSample:
      // struct RadianceSample { vec3 radiance; float throughput; vec3 direction; float pdf; }; (32 bytes)
      List<MockDataBuffers.StructFieldDef> sampleFields = List.of(
         new MockDataBuffers.StructFieldDef("RadianceSample.radiance", "vec3", GL20C.GL_FLOAT_VEC3, 0, 12, 32),
         new MockDataBuffers.StructFieldDef("RadianceSample.throughput", "float", GL11C.GL_FLOAT, 12, 4, 32),
         new MockDataBuffers.StructFieldDef("RadianceSample.direction", "vec3", GL20C.GL_FLOAT_VEC3, 16, 12, 32),
         new MockDataBuffers.StructFieldDef("RadianceSample.pdf", "float", GL11C.GL_FLOAT, 28, 4, 32)
      );

      // Step 2: Create a 64KB light bounce buffer with NaNs injected at float index 2048 (byte offset 8192)
      int totalBytes = 65536;
      int[] nanIndices = {2048, 2049}; // radiance.x and radiance.y become NaN
      ByteBuffer ssboBuffer = MockDataBuffers.createSsboBufferWithNans(totalBytes, nanIndices, null);

      // Step 3: Perform whole-buffer bitwise NaN scan
      AlgorithmVerifiers.NanScanResult scan = AlgorithmVerifiers.scanBufferFloats(ssboBuffer, 64);
      assertEquals(2L, scan.nanCount());
      assertEquals(8192L, scan.firstNanByteOffset());

      // Step 4: Map corrupted byte offset back to struct field:
      // Struct stride = 32 bytes; 8192 % 32 = 0 -> maps to RadianceSample.radiance!
      int structStride = 32;
      long firstNanByte = scan.firstNanByteOffset();
      long structIndex = firstNanByte / structStride;
      int fieldOffset = (int) (firstNanByte % structStride);

      assertEquals(256L, structIndex);
      assertEquals(0, fieldOffset);

      String corruptedField = sampleFields.stream()
         .filter(f -> fieldOffset >= f.offset() && fieldOffset < (f.offset() + f.sizeBytes()))
         .findFirst()
         .map(MockDataBuffers.StructFieldDef::name)
         .orElse("unknown");

      assertEquals("RadianceSample.radiance", corruptedField);

      // Step 5: Log incident to ErrorCapture
      ErrorCapture errorCapture = new ErrorCapture();
      errorCapture.addError("NaN Poisoning", "raytrace_radiance_ssbo",
         "NaN detected at byte " + firstNanByte + " in struct instance " + structIndex + " field: " + corruptedField, "");

      assertEquals(1, errorCapture.getErrors().size());
      assertTrue(errorCapture.getErrors().get(0).message.contains("RadianceSample.radiance"));
   }

   @Test
   @DisplayName("Scenario 5: Automated Headless Diagnostics Batch (MCP run_actions End-to-End Multi-Action Preflight)")
   void testScenario5_BatchAutomatedDiagnosticsBatchWorkflow() {
      // Build 6-action automated diagnostics batch conforming to ViewfinderToolSchemas.runActions() contract
      List<Map<String, Object>> actions = List.of(
         Map.of("type", "get_render_settings"),
         Map.of("type", "set_render_settings", "renderDistance", 12, "fov", 70),
         Map.of("type", "get_diagnostics", "includeSamples", false),
         Map.of("type", "inspect_program", "name", "composite_compute"),
         Map.of("type", "inspect_ssbo", "index", 0, "bytes", 1024),
         Map.of("type", "profile_frames", "frames", 10, "includeSamples", false)
      );

      Map<String, Object> batchRequest = Map.of("actions", actions);

      // Preflight validate the entire batch against run_actions schema
      assertDoesNotThrow(() -> {
         E2ETestContext.validateToolArguments("run_actions", batchRequest, "batch_diagnostics");
      }, "Batch diagnostic request must pass strict schema validation");

      // Verify that every individual sub-action type is a recognized action type
      for (Map<String, Object> act : actions) {
         String type = (String) act.get("type");
         assertNotNull(type);
         assertTrue(E2ETestContext.isSchemaValidJsonSchema(E2ETestContext.getToolSchema(type)));
      }
   }
}
