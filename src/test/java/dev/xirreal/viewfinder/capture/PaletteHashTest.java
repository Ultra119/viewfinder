package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class PaletteHashTest {

   @Test
   void testVoxelIdZeroIsTransparentBlack() {
      int argb = TextureDumper.voxelIdToArgb(0);
      assertEquals(0x00000000, argb, "Voxel ID 0 must be transparent black");
   }

   @Test
   void testNonZeroIdsAreOpaque() {
      for (int id = 1; id <= 100; id++) {
         int argb = TextureDumper.voxelIdToArgb(id);
         int alpha = (argb >>> 24) & 0xFF;
         assertEquals(255, alpha, "Non-zero voxel ID must be fully opaque");
      }
   }

   @Test
   void testAdjacentIdsHaveDistinctColors() {
      // Golden ratio dispersion ensures consecutive integer IDs receive high contrast hues
      for (int id = 1; id <= 50; id++) {
         int color1 = TextureDumper.voxelIdToArgb(id);
         int color2 = TextureDumper.voxelIdToArgb(id + 1);

         assertNotEquals(color1, color2, "Adjacent IDs must never have identical color");

         // Convert to HSB and verify hue distance
         float[] hsb1 = Color.RGBtoHSB((color1 >> 16) & 0xFF, (color1 >> 8) & 0xFF, color1 & 0xFF, null);
         float[] hsb2 = Color.RGBtoHSB((color2 >> 16) & 0xFF, (color2 >> 8) & 0xFF, color2 & 0xFF, null);

         float hueDiff = Math.abs(hsb1[0] - hsb2[0]);
         if (hueDiff > 0.5f) hueDiff = 1.0f - hueDiff;

         // Consecutive golden ratio steps are ~0.6180339887 % 1.0, distance on circle is ~0.381966 (~137.5 degrees)
         assertTrue(hueDiff > 0.25f, "Hue difference between ID " + id + " and " + (id + 1) + " must be large (>0.25), was " + hueDiff);
      }
   }

   @Test
   void testDeterministicMapping() {
      for (int id = -10; id <= 10; id++) {
         int first = TextureDumper.voxelIdToArgb(id);
         int second = TextureDumper.voxelIdToArgb(id);
         assertEquals(first, second, "Color mapping must be deterministic");
      }
   }
}
