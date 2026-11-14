package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import dev.xirreal.viewfinder.e2e.harness.MockDataBuffers;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 (Feature Coverage): F2 - 3D Texture & Voxel Grid Diagnostics.
 * Isolated happy-path tests verifying voxel occupancy, active bounding box, block ID histogram,
 * golden ratio discrete hash palette dispersion, and 3D slicing schemas.
 */
public class Tier1VoxelDiagnosticsTest {

   @Test
   @DisplayName("F2.1: Voxel grid diagnostics accurately calculates occupancy ratio")
   void testVoxelGridOccupancyRatioCalculation() {
      // 16x16x16 = 4096 voxels; populate exactly 512 non-zero voxels (e.g. 512 / 4096 = 0.125 = 12.5%)
      Map<String, Integer> voxels = new HashMap<>();
      int count = 0;
      for (int z = 0; z < 8; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               voxels.put(x + "," + y + "," + z, 1);
               count++;
            }
         }
      }
      assertEquals(512, count);

      IntBuffer grid = MockDataBuffers.createVoxelGrid(16, 16, 16, voxels);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(grid, 16, 16, 16, 1);

      assertEquals(4096L, diag.totalVoxels());
      assertEquals(512L, diag.occupiedVoxels());
      assertEquals(0.125, diag.occupancyRatio(), 1e-6);
   }

   @Test
   @DisplayName("F2.2: Voxel grid diagnostics accurately computes active 3D bounding box")
   void testVoxelGridActiveBoundingBoxCalculation() {
      Map<String, Integer> voxels = new HashMap<>();
      voxels.put("4,5,6", 1);
      voxels.put("20,25,30", 2);

      IntBuffer grid = MockDataBuffers.createVoxelGrid(32, 32, 32, voxels);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(grid, 32, 32, 32, 1);

      assertEquals(2L, diag.occupiedVoxels());
      assertEquals(4, diag.minBoundingBox()[0]);
      assertEquals(5, diag.minBoundingBox()[1]);
      assertEquals(6, diag.minBoundingBox()[2]);

      assertEquals(20, diag.maxBoundingBox()[0]);
      assertEquals(25, diag.maxBoundingBox()[1]);
      assertEquals(30, diag.maxBoundingBox()[2]);

      // dimensions: (20 - 4 + 1) = 17, (25 - 5 + 1) = 21, (30 - 6 + 1) = 25
      assertEquals(17, diag.dimensions()[0]);
      assertEquals(21, diag.dimensions()[1]);
      assertEquals(25, diag.dimensions()[2]);
   }

   @Test
   @DisplayName("F2.3: Voxel grid diagnostics reports block ID histogram excluding air (ID 0)")
   void testVoxelGridBlockIdHistogram() {
      Map<String, Integer> voxels = new HashMap<>();
      // 100 voxels with ID 1 (Stone)
      for (int i = 0; i < 100; i++) {
         voxels.put(i % 16 + "," + (i / 16) + ",0", 1);
      }
      // 50 voxels with ID 3 (Dirt)
      for (int i = 0; i < 50; i++) {
         voxels.put(i % 16 + "," + (i / 16) + ",1", 3);
      }

      IntBuffer grid = MockDataBuffers.createVoxelGrid(16, 16, 16, voxels);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(grid, 16, 16, 16, 1);

      assertEquals(150L, diag.occupiedVoxels());
      assertEquals(100L, diag.histogram().get(1));
      assertEquals(50L, diag.histogram().get(3));
      assertTrue(!diag.histogram().containsKey(0), "Histogram must not include air ID 0");
   }

   @Test
   @DisplayName("F2.4: Golden ratio hash palette maps integer IDs to high-contrast distinct ARGB colors")
   void testGoldenRatioHashPaletteMapping() {
      // ID 0 must map to transparent
      int color0 = AlgorithmVerifiers.computeGoldenRatioColor(0);
      assertEquals(0x00000000, color0);
      assertEquals(0x00000000, TextureDumper.voxelIdToArgb(0));

      // IDs 1, 2, 3 must have distinct high-contrast colors
      int color1 = AlgorithmVerifiers.computeGoldenRatioColor(1);
      int color2 = AlgorithmVerifiers.computeGoldenRatioColor(2);
      int color3 = AlgorithmVerifiers.computeGoldenRatioColor(3);

      assertEquals(color1, TextureDumper.voxelIdToArgb(1));
      assertEquals(color2, TextureDumper.voxelIdToArgb(2));
      assertEquals(color3, TextureDumper.voxelIdToArgb(3));

      assertNotEquals(color1, color2);
      assertNotEquals(color2, color3);
      assertNotEquals(color1, color3);

      // Verify full opacity for non-zero IDs
      assertEquals(0xFF, (color1 >> 24) & 0xFF);
      assertEquals(0xFF, (color2 >> 24) & 0xFF);
      assertEquals(0xFF, (color3 >> 24) & 0xFF);
   }

   @Test
   @DisplayName("F2.5: Tool schema dump_texture validates slice and axis parameters")
   void testDumpTextureSliceAndAxisSchemaValidation() {
      Map<String, Object> args = Map.of(
         "name", "voxelGrid",
         "slice", 8,
         "axis", "Y",
         "palette", true
      );
      assertDoesNotThrow(() -> E2ETestContext.validateToolArguments("dump_texture", args, "dump_voxel_slice"));
   }

   @Test
   @DisplayName("F2.6: Tool schema inspect_texture validates selective subvolume coordinates and voxelSummary flag")
   void testInspectTextureSubvolumeReadbackSchema() {
      Map<String, Object> args = Map.of(
         "name", "voxelGrid",
         "x", 10,
         "y", 12,
         "z", 4,
         "slice", 4,
         "axis", "Z",
         "voxelSummary", true
      );
      assertDoesNotThrow(() -> E2ETestContext.validateToolArguments("inspect_texture", args, "inspect_voxel_subvolume"));
   }
}
