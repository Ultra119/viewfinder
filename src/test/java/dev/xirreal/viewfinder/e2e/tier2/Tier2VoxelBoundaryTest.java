package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Tier 2 (Boundary & Corner Cases): F2 - 3D Texture & Voxel Grid Diagnostics.
 * Tests boundary conditions: completely empty grids, completely full grids, single isolated voxels,
 * out-of-bounds slice indices, invalid axis enums, and extreme block IDs.
 */
public class Tier2VoxelBoundaryTest {

   @Test
   @DisplayName("F2.2.1: Completely empty voxel grid reports 0.0 occupancy and zero-volume bounding box")
   void testVoxelGridCompletelyEmpty() {
      IntBuffer emptyGrid = MockDataBuffers.createVoxelGrid(16, 16, 16, Map.of());
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(emptyGrid, 16, 16, 16, 1);

      assertEquals(4096L, diag.totalVoxels());
      assertEquals(0L, diag.occupiedVoxels());
      assertEquals(0.0, diag.occupancyRatio(), 1e-9);
      assertArrayEquals(new int[] {0, 0, 0}, diag.dimensions());
      assertTrue(diag.histogram().isEmpty());
   }

   @Test
   @DisplayName("F2.2.2: Completely full voxel grid reports 1.0 occupancy and full grid bounding box")
   void testVoxelGridCompletelyFull() {
      int size = 8;
      Map<String, Integer> fullMap = new HashMap<>();
      for (int z = 0; z < size; z++) {
         for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
               fullMap.put(x + "," + y + "," + z, 7); // Bedrock
            }
         }
      }

      IntBuffer fullGrid = MockDataBuffers.createVoxelGrid(size, size, size, fullMap);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(fullGrid, size, size, size, 1);

      assertEquals(512L, diag.totalVoxels());
      assertEquals(512L, diag.occupiedVoxels());
      assertEquals(1.0, diag.occupancyRatio(), 1e-9);
      assertArrayEquals(new int[] {0, 0, 0}, diag.minBoundingBox());
      assertArrayEquals(new int[] {size - 1, size - 1, size - 1}, diag.maxBoundingBox());
      assertArrayEquals(new int[] {size, size, size}, diag.dimensions());
      assertEquals(512L, diag.histogram().get(7));
   }

   @Test
   @DisplayName("F2.2.3: Single isolated voxel placed at maximum grid corner reports [1, 1, 1] volume")
   void testVoxelGridSingleVoxelAtMaxCorner() {
      int dim = 32;
      Map<String, Integer> singleMap = Map.of((dim - 1) + "," + (dim - 1) + "," + (dim - 1), 42);
      IntBuffer grid = MockDataBuffers.createVoxelGrid(dim, dim, dim, singleMap);
      AlgorithmVerifiers.VoxelDiagnostics diag = AlgorithmVerifiers.computeVoxelDiagnostics(grid, dim, dim, dim, 1);

      assertEquals(1L, diag.occupiedVoxels());
      assertArrayEquals(new int[] {31, 31, 31}, diag.minBoundingBox());
      assertArrayEquals(new int[] {31, 31, 31}, diag.maxBoundingBox());
      assertArrayEquals(new int[] {1, 1, 1}, diag.dimensions());
      assertEquals(1L, diag.histogram().get(42));
   }

   @Test
   @DisplayName("F2.2.4: Slice index out of bounds is rejected by schema validation")
   void testSliceIndexOutOfBounds() {
      // slice must be >= 0
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("dump_texture", Map.of("name", "voxelGrid", "slice", -1), "dump_slice");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_texture", Map.of("name", "voxelGrid", "slice", -10), "inspect_slice");
      });
   }

   @Test
   @DisplayName("F2.2.5: Invalid or empty axis string is rejected by tool schema")
   void testAxisInvalidOrEmptyString() {
      // Axis must be one of ["Z", "Y", "X"]
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("dump_texture", Map.of("name", "voxelGrid", "axis", "W"), "dump_axis");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("dump_texture", Map.of("name", "voxelGrid", "axis", ""), "dump_axis");
      });
      assertThrows(IllegalArgumentException.class, () -> {
         E2ETestContext.validateToolArguments("inspect_texture", Map.of("name", "voxelGrid", "axis", "INVALID"), "inspect_axis");
      });
   }

   @Test
   @DisplayName("F2.2.6: Hash palette handles extreme block IDs without integer overflow")
   void testHashPaletteExtremeBlockIds() {
      int[] extremeIds = {0, 1, Integer.MAX_VALUE, -1, 65536, 1_000_000_000};
      for (int id : extremeIds) {
         int argb = TextureDumper.voxelIdToArgb(id);
         int verifierArgb = AlgorithmVerifiers.computeGoldenRatioColor(id);
         assertEquals(verifierArgb, argb, "Mismatch on extreme ID " + id);
         if (id == 0) {
            assertEquals(0x00000000, argb);
         } else {
            assertEquals(0xFF, (argb >> 24) & 0xFF, "Alpha must be 0xFF for non-zero ID: " + id);
         }
      }
   }
}
