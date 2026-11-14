package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.MockDataBuffers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 (Feature Coverage): F4 - HDR Tone Mapping, Heatmaps & Radiance .hdr Export.
 * Isolated happy-path tests verifying 32-bit RGBE quantization round-trip, Greg Ward scanline RLE,
 * exponential exposure tone mapping, vector remapping, and false-color colormaps.
 */
public class Tier1HdrExportToneMapTest {

   @Test
   @DisplayName("F4.1: Float to RGBE encoding converts back within 1% relative error")
   void testFloatToRgbeRoundTripConversion() {
      float[][] testColors = {
         {1.0f, 0.5f, 0.25f},
         {128.0f, 64.0f, 32.0f},
         {0.01f, 0.02f, 0.04f},
         {10.5f, 20.25f, 0.125f}
      };

      for (float[] rgb : testColors) {
         boolean ok = AlgorithmVerifiers.verifyRgbeRoundTrip(rgb[0], rgb[1], rgb[2], 0.015f);
         assertTrue(ok, "RGBE round trip failed for (" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ")");

         byte[] rgbe = new byte[4];
         TextureDumper.floatToRgbe(rgb[0], rgb[1], rgb[2], rgbe, 0);
         float[] decoded = TextureDumper.rgbeToFloat(rgbe[0], rgbe[1], rgbe[2], rgbe[3]);

         float maxVal = Math.max(Math.max(rgb[0], rgb[1]), rgb[2]);
         assertEquals(rgb[0], decoded[0], Math.max(maxVal * 0.015f, 0.001f));
         assertEquals(rgb[1], decoded[1], Math.max(maxVal * 0.015f, 0.001f));
         assertEquals(rgb[2], decoded[2], Math.max(maxVal * 0.015f, 0.001f));
      }
   }

   @Test
   @DisplayName("F4.2: Radiance .hdr stream contains standard ASCII header and scanline resolution")
   void testRadianceHdrHeaderAndScanlineStructure() throws Exception {
      int width = 8;
      int height = 8;
      FloatBuffer buffer = MockDataBuffers.createUniformRadianceBuffer(width, height, 1.0f, 2.0f, 3.0f, 1.0f);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdr(buffer, width, height, out);

      String content = out.toString(StandardCharsets.US_ASCII);
      assertTrue(content.startsWith("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n"));
      assertTrue(content.contains("-Y 8 +X 8\n"));
   }

   @Test
   @DisplayName("F4.3: Adaptive Greg Ward RLE compression decodes to exact original scanline bytes")
   void testRadianceHdrRleCompressionDecompression() throws Exception {
      int width = 64;
      byte[][] channels = new byte[4][width];
      // Create scanlines with repeating runs and literal sequences
      for (int i = 0; i < width; i++) {
         channels[0][i] = (byte) (i < 32 ? 100 : i);      // run followed by literal
         channels[1][i] = (byte) (i < 48 ? 150 : 200);    // two runs
         channels[2][i] = (byte) (i * 3);                 // varying literals
         channels[3][i] = (byte) 135;                     // constant exponent run
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);

      byte[] compressed = out.toByteArray();
      // First 4 bytes must be [2, 2, (width >> 8), (width & 0xFF)]
      assertEquals(2, compressed[0] & 0xFF);
      assertEquals(2, compressed[1] & 0xFF);
      assertEquals(0, compressed[2] & 0xFF);
      assertEquals(64, compressed[3] & 0xFF);

      ByteArrayInputStream in = new ByteArrayInputStream(compressed);
      byte[][] decoded = AlgorithmVerifiers.decodeWardRleScanline(in, width);

      for (int ch = 0; ch < 4; ch++) {
         assertArrayEquals(channels[ch], decoded[ch], "Mismatch on channel " + ch);
      }
   }

   @Test
   @DisplayName("F4.4: Exposure tone mapping calculation matches exponential response 1 - exp(-x * exposure)")
   void testExposureToneMappingCalculation() {
      float luminance = 2.0f;
      float exposure = 1.0f;

      int mapped = AlgorithmVerifiers.exposureToneMap(luminance, exposure);
      double expectedNorm = 1.0 - Math.exp(-2.0); // ~0.86466
      int expectedByte = (int) (expectedNorm * 255.0); // 220

      assertEquals(expectedByte, mapped, 1);

      TextureDumper.DumpOptions opts = new TextureDumper.DumpOptions();
      opts.toneMap = "exposure";
      opts.exposure = 1.0f;
      int argb = TextureDumper.renderFloatPixel(luminance, luminance, luminance, 1.0f, opts);
      int red = (argb >> 16) & 0xFF;
      assertEquals(expectedByte, red, 1);
   }

   @Test
   @DisplayName("F4.5: Vector remapping maps [-1, 1] surface normal to [0, 255] RGB")
   void testVectorRemappingCalculation() {
      // Normal vector (0.0, 0.0, 1.0) -> RGB (127/128, 127/128, 255)
      int mappedZ = AlgorithmVerifiers.vectorRemap(1.0f);
      int mappedXY = AlgorithmVerifiers.vectorRemap(0.0f);

      assertEquals(255, mappedZ);
      assertEquals(127, mappedXY, 1);

      TextureDumper.DumpOptions opts = new TextureDumper.DumpOptions();
      opts.vectorRemap = true;
      int argb = TextureDumper.renderFloatPixel(0.0f, 0.0f, 1.0f, 1.0f, opts);
      int r = (argb >> 16) & 0xFF;
      int g = (argb >> 8) & 0xFF;
      int b = argb & 0xFF;

      assertEquals(127, r, 1);
      assertEquals(127, g, 1);
      assertEquals(255, b);
   }

   @Test
   @DisplayName("F4.6: Turbo and Viridis colormap lookups produce valid false-color RGB values")
   void testTurboAndViridisColormapLookup() {
      int turbo0 = TextureDumper.sampleHeatmap("turbo", 0.0f, 64.0f);
      int turboMid = TextureDumper.sampleHeatmap("turbo", 32.0f, 64.0f);
      int turboMax = TextureDumper.sampleHeatmap("turbo", 64.0f, 64.0f);

      int viridis0 = TextureDumper.sampleHeatmap("viridis", 0.0f, 64.0f);
      int viridisMid = TextureDumper.sampleHeatmap("viridis", 32.0f, 64.0f);
      int viridisMax = TextureDumper.sampleHeatmap("viridis", 64.0f, 64.0f);

      // Verify alpha is 0xFF
      assertEquals(0xFF, (turbo0 >> 24) & 0xFF);
      assertEquals(0xFF, (viridis0 >> 24) & 0xFF);

      // Viridis start knot is deep purple [68, 1, 84] -> 0xFF440154
      int v0R = (viridis0 >> 16) & 0xFF;
      int v0G = (viridis0 >> 8) & 0xFF;
      int v0B = viridis0 & 0xFF;
      assertEquals(68, v0R, 1);
      assertEquals(1, v0G, 1);
      assertEquals(84, v0B, 1);

      // Viridis end knot is bright yellow [253, 231, 37] -> 0xFFFDE725
      int vMaxR = (viridisMax >> 16) & 0xFF;
      int vMaxG = (viridisMax >> 8) & 0xFF;
      int vMaxB = viridisMax & 0xFF;
      assertEquals(253, vMaxR, 1);
      assertEquals(231, vMaxG, 1);
      assertEquals(37, vMaxB, 1);
   }
}
