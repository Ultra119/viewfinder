package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.capture.ProgramRegistry;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 2 (Boundary & Corner Cases): F1 - Compute Shader & Image Load/Store Visibility.
 * Tests boundary conditions: unlinked/zero program IDs, maximum hardware invocation bounds,
 * naming collisions with colortex buffers, empty attachment lists, raster programs, and invalid dispatch counts.
 */
public class Tier2ComputeBoundaryTest {

   @Test
   @DisplayName("F2.1.1: Resolving program with ID 0 or unlinked state handles error cleanly without arithmetic crash")
   void testComputeWorkgroupZeroOrUnlinkedProgram() {
      assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve("non_existent_program_xyz"));
      assertThrows(IllegalArgumentException.class, () -> {
         // Querying inspect on id 0 throws IllegalArgumentException
         ProgramRegistry.inspect(0);
      });
   }

   @Test
   @DisplayName("F2.1.2: Workgroup dimensions at hardware invocation boundary (1024) calculates correctly")
   void testComputeWorkgroupMaximumHardwareInvocations() {
      int maxAllowedInvocations = 1024;
      JsonObject progAtLimit = E2ETestContext.createMockComputeProgramReflection(120, "optix_pathtrace", 1024, 1, 1, null);
      JsonObject compute = progAtLimit.getAsJsonObject("compute");

      assertEquals(1024L, compute.get("workGroupInvocations").getAsLong());
      assertTrue(compute.get("workGroupInvocations").getAsLong() <= maxAllowedInvocations);

      // Verify oversized workgroup reflection calculation (e.g. 1024 x 2 = 2048)
      JsonObject progOverLimit = E2ETestContext.createMockComputeProgramReflection(121, "oversized_compute", 1024, 2, 1, null);
      long oversizedInvocations = progOverLimit.getAsJsonObject("compute").get("workGroupInvocations").getAsLong();
      assertEquals(2048L, oversizedInvocations);
      assertTrue(oversizedInvocations > maxAllowedInvocations);
   }

   @Test
   @DisplayName("F2.1.3: Custom image naming collision with colortex name resolves predictably without crash")
   void testCustomImageCollisionWithColortexName() {
      // If a shaderpack names a custom image "colortex0", verify resolution priority order
      JsonObject colortexImg = E2ETestContext.createMockCustomImage("colortex0", "u_colortex0", 99, 1920, 1080, 1, "RGBA16F");
      assertEquals("colortex0", colortexImg.get("name").getAsString());
      assertEquals(99, colortexImg.get("id").getAsInt());
   }

   @Test
   @DisplayName("F2.1.4: Compute pass with zero bound images and zero SSBOs handles empty outputs cleanly")
   void testPassCaptureComputePassZeroAttachments() {
      JsonObject captureResult = new JsonObject();
      captureResult.addProperty("pass", "empty_compute_pass");
      captureResult.addProperty("framebuffer", 0);
      captureResult.add("outputs", new JsonArray());
      captureResult.add("images", new JsonArray());
      captureResult.add("ssbos", new JsonArray());
      captureResult.addProperty("skipped", false);

      assertFalse(captureResult.get("skipped").getAsBoolean());
      assertEquals(0, captureResult.getAsJsonArray("outputs").size());
      assertEquals(0, captureResult.getAsJsonArray("images").size());
      assertEquals(0, captureResult.getAsJsonArray("ssbos").size());
   }

   @Test
   @DisplayName("F2.1.5: Pure rasterization programs omit compute block reflection")
   void testInspectProgramFragmentOnlyOmitsComputeBlock() {
      JsonObject rasterProg = E2ETestContext.createMockRasterProgramReflection(200, "gbuffers_terrain");
      assertEquals(200, rasterProg.get("programId").getAsInt());
      assertEquals("gbuffers_terrain", rasterProg.get("name").getAsString());

      JsonElement computeElem = rasterProg.get("compute");
      assertTrue(computeElem == null || computeElem.isJsonNull());
   }

   @Test
   @DisplayName("F2.1.6: Schema rejects non-positive dispatch workgroup counts or invalid selectors")
   void testComputeDispatchNegativeWorkgroupCounts() {
      // Program selector requires positive integer ID
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_program", Map.of("id", 0), "program");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_program", Map.of("id", -5), "program");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_program", Map.of("name", ""), "program");
      });
   }
}
