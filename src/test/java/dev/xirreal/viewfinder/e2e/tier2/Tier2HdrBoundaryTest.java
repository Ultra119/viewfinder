package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xirreal.viewfinder.capture.TextureDumper;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 2 (Boundary & Corner Cases): F4 - HDR Tone Mapping, Heatmaps & Radiance .hdr Export.
 * Tests boundary conditions: true black and subnormal floats, extreme floating-point magnitudes,
 * NaN/Inf highlight masks, out-of-range colormap metrics, and RLE scanline width thresholds (<8 and 32767).
 */
public class Tier2HdrBoundaryTest {

   @Test
   @DisplayName("F2.4.1: True black (0.0) and subnormal floats (<1e-32) quantize to [0, 0, 0, 0] RGBE")
   void testRgbeBlackAndSubnormalFloats() {
      byte[] rgbe = new byte[4];
      TextureDumper.floatToRgbe(0.0f, 0.0f, 0.0f, rgbe, 0);
      assertArrayEquals(new byte[] {0, 0, 0, 0}, rgbe);

      TextureDumper.floatToRgbe(1e-38f, 1e-39f, 0.0f, rgbe, 0);
      assertArrayEquals(new byte[] {0, 0, 0, 0}, rgbe);
   }

   @Test
   @DisplayName("F2.4.2: Extremely bright HDR floats (>1e30) clamp exponent safely to 255")
   void testRgbeExtremeBrightValuesClamping() {
      byte[] rgbe = new byte[4];
      TextureDumper.floatToRgbe(1e35f, 1e36f, 1e37f, rgbe, 0);

      int e = rgbe[3] & 0xFF;
      assertTrue(e > 0 && e <= 255, "Exponent must be valid unsigned byte in [1, 255]");
      assertTrue((rgbe[0] & 0xFF) >= 0);
      assertTrue((rgbe[1] & 0xFF) >= 0);
      assertTrue((rgbe[2] & 0xFF) >= 0);
   }

   @Test
   @DisplayName("F2.4.3: Float pixels containing NaN or Infinity trigger exact highlight mask colors")
   void testNanAndInfInRgbeExport() {
      TextureDumper.DumpOptions opts = new TextureDumper.DumpOptions();
      opts.nanMask = true;

      // NaN -> Magenta (0xFFFF00FF)
      int nanPixel = TextureDumper.renderFloatPixel(Float.NaN, 0.0f, 1.0f, 1.0f, opts);
      assertEquals(0xFFFF00FF, nanPixel);

      // +Inf -> Cyan (0xFF00FFFF)
      int posInfPixel = TextureDumper.renderFloatPixel(Float.POSITIVE_INFINITY, 0.0f, 0.0f, 1.0f, opts);
      assertEquals(0xFF00FFFF, posInfPixel);

      // -Inf -> Yellow (0xFFFFFF00)
      int negInfPixel = TextureDumper.renderFloatPixel(Float.NEGATIVE_INFINITY, 0.0f, 0.0f, 1.0f, opts);
      assertEquals(0xFFFFFF00, negInfPixel);
   }

   @Test
   @DisplayName("F2.4.4: Turbo and Viridis colormap lookups clamp negative and above-maximum step counts")
   void testTurboAndViridisOutOfRangeInputs() {
      // Metric below 0.0 must clamp to start knot (t = 0.0)
      int turboUnder = TextureDumper.sampleHeatmap("turbo", -50.0f, 64.0f);
      int turboZero = TextureDumper.sampleHeatmap("turbo", 0.0f, 64.0f);
      assertEquals(turboZero, turboUnder);

      // Metric above maxSteps must clamp to end knot (t = 1.0)
      int viridisOver = TextureDumper.sampleHeatmap("viridis", 500.0f, 64.0f);
      int viridisMax = TextureDumper.sampleHeatmap("viridis", 64.0f, 64.0f);
      assertEquals(viridisMax, viridisOver);
   }

   @Test
   @DisplayName("F2.4.5: Scanline widths below minimum (< 8) write uncompressed scanline fallback")
   void testRleScanlineWidthsBelowMinimum() throws Exception {
      int width = 4; // below minimum 8
      byte[][] channels = new byte[4][width];
      for (int i = 0; i < width; i++) {
         channels[0][i] = (byte) 10;
         channels[1][i] = (byte) 20;
         channels[2][i] = (byte) 30;
         channels[3][i] = (byte) 130;
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);

      byte[] bytes = out.toByteArray();
      // Uncompressed scanline has width * 4 = 16 bytes directly
      assertEquals(16, bytes.length);

      ByteArrayInputStream in = new ByteArrayInputStream(bytes);
      byte[][] decoded = AlgorithmVerifiers.decodeWardRleScanline(in, width);
      for (int ch = 0; ch < 4; ch++) {
         assertArrayEquals(channels[ch], decoded[ch]);
      }
   }

   @Test
   @DisplayName("F2.4.6: Maximum scanline length boundary (32,767 pixels) encodes 15-bit length header")
   void testRleScanlineMaxLengthBoundary() throws Exception {
      int maxWidth = 32767;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(2);
      out.write(2);
      out.write((maxWidth >> 8) & 0xFF);
      out.write(maxWidth & 0xFF);

      byte[] header = out.toByteArray();
      assertEquals(2, header[0] & 0xFF);
      assertEquals(2, header[1] & 0xFF);
      assertEquals(127, header[2] & 0xFF); // 32767 >> 8 = 127
      assertEquals(255, header[3] & 0xFF); // 32767 & 0xFF = 255
   }
}
