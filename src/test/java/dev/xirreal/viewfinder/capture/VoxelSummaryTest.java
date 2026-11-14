package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class VoxelSummaryTest {

   @Test
   void testEmptyGrid() {
      int w = 8, h = 8, d = 8;
      int[] emptyGrid = new int[w * h * d];
      JsonObject summary = TextureInspector.computeVoxelSummaryFromGrid(w, h, d, emptyGrid);

      assertEquals(512L, summary.get("totalVoxels").getAsLong());
      assertEquals(0L, summary.get("occupiedVoxels").getAsLong());
      assertEquals(0.0, summary.get("occupancyRatio").getAsDouble());
      assertEquals("0.00%", summary.get("occupancyPercent").getAsString());
      assertEquals(0, summary.get("uniqueBlockIds").getAsInt());
      assertTrue(summary.get("histogram").getAsJsonArray().isEmpty());

      JsonObject aabb = summary.getAsJsonObject("activeBoundingBox");
      assertTrue(aabb.get("min").isJsonNull());
      assertTrue(aabb.get("max").isJsonNull());
      assertEquals(0L, aabb.get("volume").getAsLong());
   }

   @Test
   void testFullOccupancy() {
      int w = 4, h = 4, d = 4;
      int[] fullGrid = new int[w * h * d];
      for (int i = 0; i < fullGrid.length; i++) {
         fullGrid[i] = 1; // Stone
      }

      JsonObject summary = TextureInspector.computeVoxelSummaryFromGrid(w, h, d, fullGrid);
      assertEquals(64L, summary.get("totalVoxels").getAsLong());
      assertEquals(64L, summary.get("occupiedVoxels").getAsLong());
      assertEquals(1.0, summary.get("occupancyRatio").getAsDouble());
      assertEquals("100.00%", summary.get("occupancyPercent").getAsString());
      assertEquals(1, summary.get("uniqueBlockIds").getAsInt());

      JsonObject aabb = summary.getAsJsonObject("activeBoundingBox");
      JsonArray min = aabb.getAsJsonArray("min");
      JsonArray max = aabb.getAsJsonArray("max");
      JsonArray size = aabb.getAsJsonArray("size");

      assertEquals(0, min.get(0).getAsInt());
      assertEquals(0, min.get(1).getAsInt());
      assertEquals(0, min.get(2).getAsInt());

      assertEquals(3, max.get(0).getAsInt());
      assertEquals(3, max.get(1).getAsInt());
      assertEquals(3, max.get(2).getAsInt());

      assertEquals(4, size.get(0).getAsInt());
      assertEquals(4, size.get(1).getAsInt());
      assertEquals(4, size.get(2).getAsInt());
      assertEquals(64L, aabb.get("volume").getAsLong());

      JsonArray histogram = summary.getAsJsonArray("histogram");
      assertEquals(1, histogram.size());
      JsonObject item = histogram.get(0).getAsJsonObject();
      assertEquals(1, item.get("id").getAsInt());
      assertEquals(64L, item.get("count").getAsLong());
      assertEquals(100.0, item.get("percentage").getAsDouble());
   }

   @Test
   void testActiveBoundingBoxAndHistogram() {
      int w = 16, h = 16, d = 16;
      int[] grid = new int[w * h * d];

      // Place 10 voxels of ID 1 inside [2, 3, 4] to [5, 6, 7]
      // Place 5 voxels of ID 2 at [5, 6, 7]
      for (int x = 2; x <= 4; x++) {
         int idx = (4 * h + 3) * w + x;
         grid[idx] = 1;
      }
      for (int x = 2; x <= 4; x++) {
         int idx = (5 * h + 3) * w + x;
         grid[idx] = 1;
      }
      for (int x = 2; x <= 5; x++) {
         int idx = (6 * h + 4) * w + x;
         grid[idx] = 1;
      }
      // Total ID 1 = 3 + 3 + 4 = 10 voxels

      // Add 5 voxels of ID 2
      for (int z = 5; z <= 9; z++) {
         int idx = (z * h + 6) * w + 5;
         grid[idx] = 2;
      }

      JsonObject summary = TextureInspector.computeVoxelSummaryFromGrid(w, h, d, grid);
      assertEquals(4096L, summary.get("totalVoxels").getAsLong());
      assertEquals(15L, summary.get("occupiedVoxels").getAsLong());
      assertEquals(15.0 / 4096.0, summary.get("occupancyRatio").getAsDouble(), 1e-6);
      assertEquals(2, summary.get("uniqueBlockIds").getAsInt());

      JsonObject aabb = summary.getAsJsonObject("activeBoundingBox");
      JsonArray min = aabb.getAsJsonArray("min");
      JsonArray max = aabb.getAsJsonArray("max");
      JsonArray size = aabb.getAsJsonArray("size");

      // X spans [2, 5] -> size 4
      assertEquals(2, min.get(0).getAsInt());
      assertEquals(5, max.get(0).getAsInt());
      assertEquals(4, size.get(0).getAsInt());

      // Y spans [3, 6] -> size 4
      assertEquals(3, min.get(1).getAsInt());
      assertEquals(6, max.get(1).getAsInt());
      assertEquals(4, size.get(1).getAsInt());

      // Z spans [4, 9] -> size 6
      assertEquals(4, min.get(2).getAsInt());
      assertEquals(9, max.get(2).getAsInt());
      assertEquals(6, size.get(2).getAsInt());

      assertEquals(4L * 4L * 6L, aabb.get("volume").getAsLong());

      JsonArray histogram = summary.getAsJsonArray("histogram");
      assertEquals(2, histogram.size());

      // Sorted descending: ID 1 (10 count), then ID 2 (5 count)
      JsonObject first = histogram.get(0).getAsJsonObject();
      assertEquals(1, first.get("id").getAsInt());
      assertEquals(10L, first.get("count").getAsLong());
      assertEquals(66.67, first.get("percentage").getAsDouble(), 0.01);

      JsonObject second = histogram.get(1).getAsJsonObject();
      assertEquals(2, second.get("id").getAsInt());
      assertEquals(5L, second.get("count").getAsLong());
      assertEquals(33.33, second.get("percentage").getAsDouble(), 0.01);
   }
}
