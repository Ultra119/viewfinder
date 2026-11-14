package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class RadianceHdrTest {

   @Test
   void testFloatToRgbeZero() {
      byte[] rgbe = new byte[4];
      TextureDumper.floatToRgbe(0.0f, 0.0f, 0.0f, rgbe, 0);
      assertArrayEquals(new byte[] {0, 0, 0, 0}, rgbe);

      float[] decoded = TextureDumper.rgbeToFloat(rgbe[0], rgbe[1], rgbe[2], rgbe[3]);
      assertEquals(0.0f, decoded[0]);
      assertEquals(0.0f, decoded[1]);
      assertEquals(0.0f, decoded[2]);
   }

   @Test
   void testFloatToRgbeRoundtrip() {
      float[][] testColors = {
         {1.0f, 0.5f, 0.25f},
         {10.0f, 20.0f, 30.0f},
         {0.1f, 0.2f, 0.3f},
         {128.5f, 64.25f, 32.125f},
         {0.005f, 0.01f, 0.02f}
      };

      byte[] rgbe = new byte[4];
      for (float[] color : testColors) {
         TextureDumper.floatToRgbe(color[0], color[1], color[2], rgbe, 0);
         // Exponent must be non-zero for non-zero colors
         assertTrue((rgbe[3] & 0xFF) > 0);

         float[] decoded = TextureDumper.rgbeToFloat(rgbe[0], rgbe[1], rgbe[2], rgbe[3]);
         // Radiance RGBE has 8-bit mantissa per channel (~1% precision)
         assertEquals(color[0], decoded[0], color[0] * 0.02f + 1e-4f);
         assertEquals(color[1], decoded[1], color[1] * 0.02f + 1e-4f);
         assertEquals(color[2], decoded[2], color[2] * 0.02f + 1e-4f);
      }
   }

   @Test
   void testScanlineRleEncodingAndDecoding() throws Exception {
      int width = 16;
      byte[][] channels = new byte[4][width];
      // Channel 0: all identical (run of 16)
      for (int i = 0; i < width; i++) channels[0][i] = (byte) 42;
      // Channel 1: all distinct (literal run)
      for (int i = 0; i < width; i++) channels[1][i] = (byte) (i * 7);
      // Channel 2: mixed run and literals
      for (int i = 0; i < 6; i++) channels[2][i] = (byte) 10;
      for (int i = 6; i < 12; i++) channels[2][i] = (byte) (i + 20);
      for (int i = 12; i < 16; i++) channels[2][i] = (byte) 99;
      // Channel 3: constant exponent
      for (int i = 0; i < width; i++) channels[3][i] = (byte) 130;

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      TextureDumper.writeHdrScanlineRle(out, channels, width);

      byte[] compressed = out.toByteArray();
      // Must begin with RLE scanline marker: 0x02, 0x02, (width >> 8), (width & 0xFF)
      assertEquals((byte) 2, compressed[0]);
      assertEquals((byte) 2, compressed[1]);
      assertEquals((byte) (width >> 8), compressed[2]);
      assertEquals((byte) (width & 0xFF), compressed[3]);

      // Decompress and verify channels match exactly
      ByteArrayInputStream in = new ByteArrayInputStream(compressed);
      assertEquals(2, in.read());
      assertEquals(2, in.read());
      int wHi = in.read();
      int wLo = in.read();
      assertEquals(width, (wHi << 8) | wLo);

      for (int ch = 0; ch < 4; ch++) {
         byte[] decompressed = new byte[width];
         int cursor = 0;
         while (cursor < width) {
            int code = in.read();
            if (code > 128) {
               int runLen = code - 128;
               int val = in.read();
               for (int r = 0; r < runLen; r++) {
                  decompressed[cursor++] = (byte) val;
               }
            } else {
               int litLen = code;
               for (int l = 0; l < litLen; l++) {
                  decompressed[cursor++] = (byte) in.read();
               }
            }
         }
         assertArrayEquals(channels[ch], decompressed, "Mismatch in channel " + ch);
      }
   }

   @Test
   void testFullHdrFileExport() throws Exception {
      int width = 10;
      int height = 4;
      FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
      try {
         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int i = (y * width + x) * 4;
               buffer.put(i, (float) (x + 1));
               buffer.put(i + 1, (float) (y + 1));
               buffer.put(i + 2, 0.5f);
               buffer.put(i + 3, 1.0f);
            }
         }

         ByteArrayOutputStream out = new ByteArrayOutputStream();
         TextureDumper.writeHdr(buffer, width, height, out);

         String content = out.toString(StandardCharsets.US_ASCII);
         assertTrue(content.startsWith("#?RADIANCE\n"));
         assertTrue(content.contains("FORMAT=32-bit_rle_rgbe\n"));
         assertTrue(content.contains("-Y " + height + " +X " + width + "\n"));
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }
}
