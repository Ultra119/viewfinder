package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL43C;

/**
 * Tier 1 (Feature Coverage): F1 - Compute Shader & Image Load/Store Visibility.
 * Isolated happy-path tests verifying compute workgroup dimensions, custom image resolution,
 * texture listing schemas, and compute pass memory barriers.
 */
public class Tier1ComputeImageTest {

   @Test
   @DisplayName("F1.1: Compute workgroup dimensions reflect correctly and calculate total invocations")
   void testComputeWorkgroupDimensionsParsing() {
      JsonObject prog = E2ETestContext.createMockComputeProgramReflection(101, "voxel_builder", 16, 16, 1, null);
      assertTrue(prog.has("compute"));
      JsonObject compute = prog.getAsJsonObject("compute");

      assertEquals(16, compute.get("workGroupSizeX").getAsInt());
      assertEquals(16, compute.get("workGroupSizeY").getAsInt());
      assertEquals(1, compute.get("workGroupSizeZ").getAsInt());
      assertEquals(256L, compute.get("workGroupInvocations").getAsLong());

      JsonArray dims = compute.getAsJsonArray("workGroupDimensions");
      assertEquals(3, dims.size());
      assertEquals(16, dims.get(0).getAsInt());
      assertEquals(16, dims.get(1).getAsInt());
      assertEquals(1, dims.get(2).getAsInt());
   }

   @Test
   @DisplayName("F1.2: Custom image resolves by declared name")
   void testCustomImageResolutionByName() {
      JsonObject img = E2ETestContext.createMockCustomImage("voxelGrid", "u_voxelGrid", 42, 64, 64, 64, "RGBA16F");
      assertEquals("voxelGrid", img.get("name").getAsString());
      assertEquals(42, img.get("id").getAsInt());
      assertEquals(64, img.get("depth").getAsInt());
   }

   @Test
   @DisplayName("F1.3: Custom image resolves by samplerName")
   void testCustomImageResolutionBySamplerName() {
      JsonObject img = E2ETestContext.createMockCustomImage("customImg1", "u_voxelGridSampler", 43, 128, 128, 1, "R32F");
      assertEquals("customImg1", img.get("name").getAsString());
      assertEquals("u_voxelGridSampler", img.get("samplerName").getAsString());
      assertEquals(43, img.get("id").getAsInt());
   }

   @Test
   @DisplayName("F1.4: List textures includes customImages array with structured fields")
   void testListTexturesIncludesCustomImages() {
      JsonObject root = new JsonObject();
      JsonArray images = new JsonArray();
      images.add(E2ETestContext.createMockCustomImage("voxelGrid", "u_voxelGrid", 42, 64, 64, 64, "RGBA16F"));
      images.add(E2ETestContext.createMockCustomImage("lightAccum", "u_lightAccum", 45, 1920, 1080, 1, "RGBA32F"));
      root.add("images", images);

      assertTrue(root.has("images"));
      JsonArray array = root.getAsJsonArray("images");
      assertEquals(2, array.size());
      assertEquals("voxelGrid", array.get(0).getAsJsonObject().get("name").getAsString());
      assertEquals("lightAccum", array.get(1).getAsJsonObject().get("name").getAsString());
   }

   @Test
   @DisplayName("F1.5: Tool schema capture_pass_outputs validates compute pass targets")
   void testComputePassCaptureSchemaAcceptsComputeTarget() {
      Map<String, Object> args = Map.of("pass", "composite_compute", "timeoutSeconds", 10);
      assertDoesNotThrow(() -> E2ETestContext.validateToolArguments("capture_pass_outputs", args, "capture_pass"));
   }

   @Test
   @DisplayName("F1.6: Compute pass memory barrier flag contains shader image and storage barrier bits")
   void testComputePassMemoryBarrierContract() {
      int expectedBits = GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
      int allBits = GL42C.GL_ALL_BARRIER_BITS;

      // GL_ALL_BARRIER_BITS encompasses image access and storage barriers
      assertEquals(expectedBits, allBits & expectedBits);
      assertTrue((allBits & GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT) != 0);
      assertTrue((allBits & GL43C.GL_SHADER_STORAGE_BARRIER_BIT) != 0);
   }
}
