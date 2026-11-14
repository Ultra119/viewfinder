package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial test suite for 3D texture slicing, Radiance .hdr RGBE/RLE compression,
 * golden ratio voxel color palette, and voxel grid diagnostic calculations.
 */
class TextureDiagnosticsAdversarialTest {

   @Test
   @DisplayName("Voxel golden ratio hash palette handles negative IDs and Integer.MIN_VALUE/MAX_VALUE")
   void testVoxelIdExtremeIntegers() {
      // ID 0 is transparent black
      assertEquals(0x00000000, TextureDumper.voxelIdToArgb(0));

      // Negative IDs (from signed voxel formats) must be fully opaque
      int negOne = TextureDumper.voxelIdToArgb(-1);
      assertEquals(255, (negOne >>> 24) & 0xFF);

      int minVal = TextureDumper.voxelIdToArgb(Integer.MIN_VALUE);
      assertEquals(255, (minVal >>> 24) & 0xFF);

      int maxVal = TextureDumper.voxelIdToArgb(Integer.MAX_VALUE);
      assertEquals(255, (maxVal >>> 24) & 0xFF);

      assertNotEquals(negOne, minVal);
      assertNotEquals(minVal, maxVal);
   }

   @Test
   @DisplayName("Radiance .hdr scanline RLE encodes and decodes scanlines with width < 8 uncompressed")
   void testRadianceRleWidthBelow8Fallback() throws Exception {
      int width = 7; // < 8 requires uncompressed fallback
      byte[][] channels = new byte[4][width];
      for (int ch = 0; ch < 4; ch++) {
         for (int i = 0; i < width; i++) {
            channels[ch][i] = (byte) ((ch + 1) * (i + 5));
         }
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);
      byte[] encoded = out.toByteArray();

      // In uncompressed mode, it writes exactly width * 4 bytes
      assertEquals(width * 4, encoded.length);

      // Verify channel interleaving [R0, G0, B0, E0, R1, ...]
      for (int i = 0; i < width; i++) {
         assertEquals(channels[0][i], encoded[i * 4]);
         assertEquals(channels[1][i], encoded[i * 4 + 1]);
         assertEquals(channels[2][i], encoded[i * 4 + 2]);
         assertEquals(channels[3][i], encoded[i * 4 + 3]);
      }
   }

   @Test
   @DisplayName("Radiance .hdr RLE splits run lengths exceeding 127 bytes correctly")
   void testRadianceRleLongRunLengthSplitting() throws Exception {
      int width = 300; // Run of 300 identical bytes (exceeds single RLE command limit of 127)
      byte[][] channels = new byte[4][width];
      for (int ch = 0; ch < 4; ch++) {
         Arrays.fill(channels[ch], (byte) 0xAA);
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);
      byte[] encoded = out.toByteArray();

      // Decompress and verify
      ByteArrayInputStream in = new ByteArrayInputStream(encoded);
      assertEquals(2, in.read());
      assertEquals(2, in.read());
      int w = (in.read() << 8) | in.read();
      assertEquals(width, w);

      for (int ch = 0; ch < 4; ch++) {
         byte[] decompressed = new byte[width];
         int cursor = 0;
         while (cursor < width) {
            int code = in.read();
            assertTrue(code > 128, "Must be an RLE run code");
            int runLen = code - 128;
            assertTrue(runLen <= 127, "Single RLE run length must never exceed 127");
            int val = in.read();
            for (int r = 0; r < runLen; r++) {
               decompressed[cursor++] = (byte) val;
            }
         }
         assertArrayEquals(channels[ch], decompressed);
      }
   }

   @Test
   @DisplayName("Radiance .hdr RLE splits literal runs exceeding 128 bytes correctly")
   void testRadianceRleLongLiteralSplitting() throws Exception {
      int width = 200; // 200 distinct bytes (exceeds single literal limit of 128)
      byte[][] channels = new byte[4][width];
      for (int ch = 0; ch < 4; ch++) {
         for (int i = 0; i < width; i++) {
            channels[ch][i] = (byte) (i ^ (ch * 43));
         }
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);
      byte[] encoded = out.toByteArray();

      ByteArrayInputStream in = new ByteArrayInputStream(encoded);
      assertEquals(2, in.read());
      assertEquals(2, in.read());
      int w = (in.read() << 8) | in.read();
      assertEquals(width, w);

      for (int ch = 0; ch < 4; ch++) {
         byte[] decompressed = new byte[width];
         int cursor = 0;
         while (cursor < width) {
            int code = in.read();
            if (code > 128) {
               int runLen = code - 128;
               int val = in.read();
               for (int r = 0; r < runLen; r++) decompressed[cursor++] = (byte) val;
            } else {
               int litLen = code;
               assertTrue(litLen <= 128, "Single literal length must not exceed 128");
               for (int l = 0; l < litLen; l++) decompressed[cursor++] = (byte) in.read();
            }
         }
         assertArrayEquals(channels[ch], decompressed);
      }
   }

   @Test
   @DisplayName("Radiance .hdr floatToRgbe clamps negative radiance values to zero")
   void testFloatToRgbeNegativeValuesClamped() {
      byte[] rgbe = new byte[4];
      TextureDumper.floatToRgbe(-5.0f, -10.0f, -0.5f, rgbe, 0);
      assertArrayEquals(new byte[] {0, 0, 0, 0}, rgbe);
   }

   @Test
   @DisplayName("Sample heatmap clamps values exceeding maxSteps to 255 index")
   void testSampleHeatmapBoundaryClamping() {
      int turboMax = TextureDumper.sampleHeatmap("turbo", 1000.0f, 64.0f);
      int turboExpected = 0xFF000000 | (TextureDumper.TURBO_LUT[255] & 0x00FFFFFF);
      assertEquals(turboExpected, turboMax);

      int viridisMin = TextureDumper.sampleHeatmap("viridis", -10.0f, 64.0f);
      int viridisExpected = 0xFF000000 | (TextureDumper.VIRIDIS_LUT[0] & 0x00FFFFFF);
      assertEquals(viridisExpected, viridisMin);
   }

   @Test
   @DisplayName("Exposure tone mapping clamps zero or negative exposure to 1.0f fallback")
   void testExposureToneMappingFallbackOnNonPositive() {
      TextureDumper.DumpOptions opts = new TextureDumper.DumpOptions();
      opts.toneMap = "exposure";
      opts.exposure = -2.0f; // negative exposure

      // Must fallback to 1.0f exposure without NaN or crash
      int pixel = TextureDumper.renderFloatPixel(1.0f, 1.0f, 1.0f, 1.0f, opts);
      int expectedByte = (int) ((1.0 - Math.exp(-1.0)) * 255.0);
      assertEquals(expectedByte, (pixel >> 16) & 0xFF, 1);
   }

   @Test
   @DisplayName("Voxel summary on 1x1x1 single voxel calculates bounding box and histogram")
   void testVoxelSummarySingleVoxel() {
      Map<Integer, Long> counts = new HashMap<>();
      counts.put(42, 1L);

      JsonObject summary = TextureInspector.buildVoxelSummary(1, 1, 1, 1L, 1L, 0, 0, 0, 0, 0, 0, counts);
      assertEquals(1.0, summary.get("occupancyRatio").getAsDouble());
      assertEquals("100.00%", summary.get("occupancyPercent").getAsString());

      JsonObject aabb = summary.getAsJsonObject("activeBoundingBox");
      assertEquals(1L, aabb.get("volume").getAsLong());
      assertEquals(0, aabb.getAsJsonArray("min").get(0).getAsInt());
      assertEquals(0, aabb.getAsJsonArray("max").get(0).getAsInt());

      JsonArray hist = summary.getAsJsonArray("histogram");
      assertEquals(1, hist.size());
      assertEquals(42, hist.get(0).getAsJsonObject().get("id").getAsInt());
      assertEquals(100.0, hist.get(0).getAsJsonObject().get("percentage").getAsDouble());
   }
}
