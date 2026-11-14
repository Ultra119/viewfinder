package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextureToneMappingAndMaskTest {

   @Test
   void testNaNHighlightMaskIsMagenta() {
      TextureDumper.DumpOptions options = new TextureDumper.DumpOptions();
      options.nanMask = true;

      // NaN in R, G, B, or A must yield Magenta 0xFFFF00FF
      assertEquals(0xFFFF00FF, TextureDumper.renderFloatPixel(Float.NaN, 0.5f, 0.5f, 1.0f, options));
      assertEquals(0xFFFF00FF, TextureDumper.renderFloatPixel(0.5f, Float.NaN, 0.5f, 1.0f, options));
      assertEquals(0xFFFF00FF, TextureDumper.renderFloatPixel(0.5f, 0.5f, Float.NaN, 1.0f, options));
      assertEquals(0xFFFF00FF, TextureDumper.renderFloatPixel(0.5f, 0.5f, 0.5f, Float.NaN, options));
   }

   @Test
   void testPositiveInfHighlightMaskIsCyan() {
      TextureDumper.DumpOptions options = new TextureDumper.DumpOptions();
      options.nanMask = true;

      // +Inf must yield Cyan 0xFF00FFFF
      assertEquals(0xFF00FFFF, TextureDumper.renderFloatPixel(Float.POSITIVE_INFINITY, 0.0f, 0.0f, 1.0f, options));
      assertEquals(0xFF00FFFF, TextureDumper.renderFloatPixel(0.0f, Float.POSITIVE_INFINITY, 0.0f, 1.0f, options));
   }

   @Test
   void testNegativeInfHighlightMaskIsYellow() {
      TextureDumper.DumpOptions options = new TextureDumper.DumpOptions();
      options.nanMask = true;

      // -Inf must yield Yellow 0xFFFFFF00
      assertEquals(0xFFFFFF00, TextureDumper.renderFloatPixel(Float.NEGATIVE_INFINITY, 0.0f, 0.0f, 1.0f, options));
      assertEquals(0xFFFFFF00, TextureDumper.renderFloatPixel(0.0f, Float.NEGATIVE_INFINITY, 0.0f, 1.0f, options));
   }

   @Test
   void testExposureToneMapping() {
      TextureDumper.DumpOptions options = new TextureDumper.DumpOptions();
      options.toneMap = "exposure";
      options.exposure = 1.5f;

      // At x = 0, 1 - exp(0) = 0
      int black = TextureDumper.renderFloatPixel(0.0f, 0.0f, 0.0f, 1.0f, options);
      assertEquals(0, (black >> 16) & 0xFF);
      assertEquals(0, (black >> 8) & 0xFF);
      assertEquals(0, black & 0xFF);

      // At x = 1.0 with exposure = 1.5: 1 - exp(-1.5) = 1 - 0.2231 = 0.7768 -> 198
      int expectedByte = (int) ((1.0 - Math.exp(-1.5)) * 255.0);
      int color = TextureDumper.renderFloatPixel(1.0f, 1.0f, 1.0f, 1.0f, options);
      assertEquals(expectedByte, (color >> 16) & 0xFF, 1);
      assertEquals(expectedByte, (color >> 8) & 0xFF, 1);
      assertEquals(expectedByte, color & 0xFF, 1);

      // At very large values, approaches 255 without overflow
      int saturated = TextureDumper.renderFloatPixel(100.0f, 100.0f, 100.0f, 1.0f, options);
      assertEquals(255, (saturated >> 16) & 0xFF);
      assertEquals(255, (saturated >> 8) & 0xFF);
      assertEquals(255, saturated & 0xFF);
   }

   @Test
   void testVectorRemapping() {
      TextureDumper.DumpOptions options = new TextureDumper.DumpOptions();
      options.vectorRemap = true;

      // v = -1.0 -> 0
      int minColor = TextureDumper.renderFloatPixel(-1.0f, -1.0f, -1.0f, 1.0f, options);
      assertEquals(0, (minColor >> 16) & 0xFF);
      assertEquals(0, (minColor >> 8) & 0xFF);
      assertEquals(0, minColor & 0xFF);

      // v = 0.0 -> 127
      int midColor = TextureDumper.renderFloatPixel(0.0f, 0.0f, 0.0f, 1.0f, options);
      assertEquals(127, (midColor >> 16) & 0xFF, 1);
      assertEquals(127, (midColor >> 8) & 0xFF, 1);
      assertEquals(127, midColor & 0xFF, 1);

      // v = 1.0 -> 255
      int maxColor = TextureDumper.renderFloatPixel(1.0f, 1.0f, 1.0f, 1.0f, options);
      assertEquals(255, (maxColor >> 16) & 0xFF);
      assertEquals(255, (maxColor >> 8) & 0xFF);
      assertEquals(255, maxColor & 0xFF);
   }

   @Test
   void testTurboAndViridisLuts() {
      assertEquals(256, TextureDumper.TURBO_LUT.length);
      assertEquals(256, TextureDumper.VIRIDIS_LUT.length);

      // Viridis starts with dark purple and ends with bright yellow
      int viridisStart = TextureDumper.VIRIDIS_LUT[0];
      int viridisEnd = TextureDumper.VIRIDIS_LUT[255];
      int vStartR = (viridisStart >> 16) & 0xFF;
      int vStartG = (viridisStart >> 8) & 0xFF;
      int vStartB = viridisStart & 0xFF;
      int vEndR = (viridisEnd >> 16) & 0xFF;
      int vEndG = (viridisEnd >> 8) & 0xFF;
      int vEndB = viridisEnd & 0xFF;

      assertTrue(vStartB > vStartG, "Viridis start must have blue > green (purple/violet)");
      assertTrue(vEndR > 200 && vEndG > 200 && vEndB < 100, "Viridis end must be yellow (high R, high G, low B)");

      // Heatmap sampling helper
      int sampledMidTurbo = TextureDumper.sampleHeatmap("turbo", 32.0f, 64.0f);
      assertEquals(0xFF, (sampledMidTurbo >>> 24) & 0xFF, "Heatmap sample must be opaque");
   }
}
