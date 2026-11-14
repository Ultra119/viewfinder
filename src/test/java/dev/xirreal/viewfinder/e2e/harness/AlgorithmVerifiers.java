package dev.xirreal.viewfinder.e2e.harness;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone algorithmic reference implementations and verifiers for pure computational correctness:
 * - Greg Ward 32-bit Radiance .hdr RGBE encoding/decoding and adaptive scanline RLE compression/decompression
 * - Golden ratio hue dispersion hash color palette mapping for voxel IDs
 * - Exposure tone mapping (1 - exp(-x * exposure)) and vector remapping ((v + 1)/2 * 255)
 * - 256-entry Turbo and Viridis colormap LUT lookups
 * - Bitwise 32-bit float whole-buffer NaN/Inf scanner
 * - 3D voxel grid diagnostics (occupancy ratio, active bounding box, block ID histogram)
 * - Multi-frame RMSE convergence profiler
 */
public final class AlgorithmVerifiers {

   private AlgorithmVerifiers() {}

   // ==========================================
   // 1. Greg Ward 32-bit Radiance RGBE & RLE
   // ==========================================

   public static void encodeRgbe(float r, float g, float b, byte[] out, int offset) {
      float maxVal = Math.max(Math.max(r, g), b);
      if (maxVal < 1e-32f) {
         out[offset] = 0;
         out[offset + 1] = 0;
         out[offset + 2] = 0;
         out[offset + 3] = 0;
         return;
      }
      int exp = Math.getExponent(maxVal) + 1;
      float scale = (float) Math.scalb(1.0f, -exp) * 256.0f;
      out[offset]     = (byte) Math.min(255, Math.max(0, (int) (r * scale)));
      out[offset + 1] = (byte) Math.min(255, Math.max(0, (int) (g * scale)));
      out[offset + 2] = (byte) Math.min(255, Math.max(0, (int) (b * scale)));
      out[offset + 3] = (byte) Math.max(0, Math.min(255, exp + 128));
   }

   public static float[] decodeRgbe(byte r, byte g, byte b, byte e) {
      int exp = e & 0xFF;
      if (exp == 0) {
         return new float[] {0.0f, 0.0f, 0.0f};
      }
      float scale = (float) Math.scalb(1.0f, exp - (128 + 8));
      return new float[] {
         (r & 0xFF) * scale,
         (g & 0xFF) * scale,
         (b & 0xFF) * scale
      };
   }

   public static boolean verifyRgbeRoundTrip(float r, float g, float b, float relativeTolerance) {
      byte[] rgbe = new byte[4];
      encodeRgbe(r, g, b, rgbe, 0);
      float[] decoded = decodeRgbe(rgbe[0], rgbe[1], rgbe[2], rgbe[3]);

      float maxInput = Math.max(Math.max(r, g), b);
      if (maxInput < 1e-30f) {
         return decoded[0] == 0.0f && decoded[1] == 0.0f && decoded[2] == 0.0f;
      }

      float errR = Math.abs(r - decoded[0]) / maxInput;
      float errG = Math.abs(g - decoded[1]) / maxInput;
      float errB = Math.abs(b - decoded[2]) / maxInput;

      return errR <= relativeTolerance && errG <= relativeTolerance && errB <= relativeTolerance;
   }

   /**
    * Decodes a Greg Ward adaptive RLE-compressed scanline from an input stream.
    * Returns 4 channel byte arrays [R, G, B, E] each of length width.
    */
   public static byte[][] decodeWardRleScanline(InputStream in, int width) throws Exception {
      byte[][] channels = new byte[4][width];

      if (width < 8 || width > 32767) {
         // Uncompressed scanline: 4 bytes per pixel directly
         for (int i = 0; i < width; i++) {
            channels[0][i] = (byte) in.read();
            channels[1][i] = (byte) in.read();
            channels[2][i] = (byte) in.read();
            channels[3][i] = (byte) in.read();
         }
         return channels;
      }

      int b0 = in.read();
      int b1 = in.read();
      int b2 = in.read();
      int b3 = in.read();

      if (b0 != 2 || b1 != 2 || ((b2 << 8) | b3) != width) {
         throw new IllegalArgumentException("Invalid Greg Ward RLE scanline header: [" + b0 + ", " + b1 + ", " + b2 + ", " + b3 + "], expected width " + width);
      }

      for (int ch = 0; ch < 4; ch++) {
         byte[] channel = channels[ch];
         int cursor = 0;
         while (cursor < width) {
            int code = in.read();
            if (code == -1) throw new IllegalArgumentException("Unexpected EOF while decoding channel " + ch);
            if (code > 128) {
               int runLen = code - 128;
               int val = in.read();
               if (val == -1) throw new IllegalArgumentException("Unexpected EOF in RLE run");
               for (int i = 0; i < runLen && cursor < width; i++) {
                  channel[cursor++] = (byte) val;
               }
            } else {
               int litLen = code;
               for (int i = 0; i < litLen && cursor < width; i++) {
                  int val = in.read();
                  if (val == -1) throw new IllegalArgumentException("Unexpected EOF in RLE literal");
                  channel[cursor++] = (byte) val;
               }
            }
         }
      }
      return channels;
   }

   // ==========================================
   // 2. Golden Ratio Hue Dispersion Hash Palette
   // ==========================================

   public static int computeGoldenRatioColor(int id) {
      if (id == 0) {
         return 0x00000000; // transparent
      }
      double rawHue = (id * 0.618033988749895) % 1.0;
      if (rawHue < 0.0) {
         rawHue += 1.0;
      }
      float hue = (float) rawHue;
      float sat = 0.75f + (float) ((id & 3) * 0.07f);
      float bri = 0.85f + (float) (((id >> 2) & 3) * 0.04f);
      int rgb = Color.HSBtoRGB(hue, sat, bri);
      return 0xFF000000 | (rgb & 0x00FFFFFF);
   }

   // ==========================================
   // 3. Exposure Tone Mapping & Vector Remapping
   // ==========================================

   public static int exposureToneMap(float x, float exposure) {
      float exp = exposure > 0.0f ? exposure : 1.0f;
      float mapped = 1.0f - (float) Math.exp(-Math.max(0.0f, x) * exp);
      return Math.max(0, Math.min(255, (int) (mapped * 255.0f)));
   }

   public static int vectorRemap(float v) {
      float remapped = (v + 1.0f) * 0.5f;
      return Math.max(0, Math.min(255, (int) (remapped * 255.0f)));
   }

   // ==========================================
   // 4. Turbo & Viridis False-Color Lookups
   // ==========================================

   public static int[] getTurboLut() {
      int[] lut = new int[256];
      for (int i = 0; i < 256; i++) {
         double x = i / 255.0;
         double r = 0.13572138 + x * (4.61539260 + x * (-42.66032258 + x * (132.13108234 + x * (-152.94239396 + x * 59.28637943))));
         double g = 0.09140261 + x * (2.19418839 + x * (4.84296658 + x * (-14.18503333 + x * (4.27729857 + x * 2.82956604))));
         double b = 0.10667330 + x * (12.64194608 + x * (-60.58204836 + x * (110.36275817 + x * (-89.90310912 + x * 27.34824973))));
         int ir = Math.max(0, Math.min(255, (int) Math.round(r * 255.0)));
         int ig = Math.max(0, Math.min(255, (int) Math.round(g * 255.0)));
         int ib = Math.max(0, Math.min(255, (int) Math.round(b * 255.0)));
         lut[i] = (ir << 16) | (ig << 8) | ib;
      }
      return lut;
   }

   public static int[] getViridisLut() {
      int[][] knots = {
         {68, 1, 84}, {72, 40, 120}, {62, 74, 137}, {49, 104, 142},
         {38, 130, 142}, {31, 158, 137}, {53, 183, 121}, {109, 205, 89},
         {253, 231, 37}
      };
      int[] knotIndices = {0, 32, 64, 96, 128, 160, 192, 224, 255};
      int[] lut = new int[256];
      for (int i = 0; i < 256; i++) {
         int seg = 0;
         while (seg < knotIndices.length - 2 && i > knotIndices[seg + 1]) {
            seg++;
         }
         int i0 = knotIndices[seg];
         int i1 = knotIndices[seg + 1];
         double t = (double) (i - i0) / (i1 - i0);
         int r = (int) Math.round(knots[seg][0] + t * (knots[seg + 1][0] - knots[seg][0]));
         int g = (int) Math.round(knots[seg][1] + t * (knots[seg + 1][1] - knots[seg][1]));
         int b = (int) Math.round(knots[seg][2] + t * (knots[seg + 1][2] - knots[seg][2]));
         lut[i] = (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
      }
      return lut;
   }

   public static int lookupColormap(String name, float value, float maxSteps) {
      float steps = maxSteps > 0.0f ? maxSteps : 64.0f;
      float clamped = Math.max(0.0f, Math.min(1.0f, value / steps));
      int lutIndex = Math.min(255, Math.max(0, (int) (clamped * 255.0f + 0.5f)));
      int[] lut = "viridis".equalsIgnoreCase(name) ? getViridisLut() : getTurboLut();
      return 0xFF000000 | (lut[lutIndex] & 0x00FFFFFF);
   }

   // ==========================================
   // 5. Bitwise 32-bit Float NaN / Inf Scanner
   // ==========================================

   public record NanScanResult(
      long totalFloats,
      long nanCount,
      long infiniteCount,
      long positiveInfCount,
      long negativeInfCount,
      long finiteCount,
      long firstNanByteOffset,
      long firstInfByteOffset,
      List<Long> nanByteOffsets
   ) {}

   public static NanScanResult scanBufferFloats(ByteBuffer buffer, int maxOffsetsToReport) {
      int limit = buffer.limit();
      long totalFloats = limit / 4;
      long nanCount = 0;
      long infiniteCount = 0;
      long positiveInfCount = 0;
      long negativeInfCount = 0;
      long finiteCount = 0;
      long firstNanByteOffset = -1;
      long firstInfByteOffset = -1;
      List<Long> nanByteOffsets = new ArrayList<>();

      for (int i = 0; i < totalFloats; i++) {
         int byteOffset = i * 4;
         int bits = buffer.getInt(byteOffset);
         int exp = (bits & 0x7F800000);
         int mantissa = (bits & 0x007FFFFF);
         boolean isNeg = ((bits & 0x80000000) != 0);

         if (exp == 0x7F800000) {
            if (mantissa != 0) {
               // NaN
               nanCount++;
               if (firstNanByteOffset == -1) firstNanByteOffset = byteOffset;
               if (nanByteOffsets.size() < maxOffsetsToReport) {
                  nanByteOffsets.add((long) byteOffset);
               }
            } else {
               // Infinity
               infiniteCount++;
               if (firstInfByteOffset == -1) firstInfByteOffset = byteOffset;
               if (isNeg) {
                  negativeInfCount++;
               } else {
                  positiveInfCount++;
               }
            }
         } else {
            finiteCount++;
         }
      }

      return new NanScanResult(
         totalFloats,
         nanCount,
         infiniteCount,
         positiveInfCount,
         negativeInfCount,
         finiteCount,
         firstNanByteOffset,
         firstInfByteOffset,
         nanByteOffsets
      );
   }

   // ==========================================
   // 6. 3D Voxel Grid Diagnostics
   // ==========================================

   public record VoxelDiagnostics(
      long totalVoxels,
      long occupiedVoxels,
      double occupancyRatio,
      int[] minBoundingBox,
      int[] maxBoundingBox,
      int[] dimensions,
      Map<Integer, Long> histogram
   ) {}

   public static VoxelDiagnostics computeVoxelDiagnostics(IntBuffer buffer, int sizeX, int sizeY, int sizeZ, int components) {
      long totalVoxels = (long) sizeX * sizeY * sizeZ;
      long occupied = 0;
      int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      Map<Integer, Long> histogram = new HashMap<>();

      for (int z = 0; z < sizeZ; z++) {
         for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
               int idx = ((z * sizeY + y) * sizeX + x) * components;
               int id = buffer.get(idx);
               if (id != 0) {
                  occupied++;
                  if (x < minX) minX = x;
                  if (y < minY) minY = y;
                  if (z < minZ) minZ = z;
                  if (x > maxX) maxX = x;
                  if (y > maxY) maxY = y;
                  if (z > maxZ) maxZ = z;
                  histogram.put(id, histogram.getOrDefault(id, 0L) + 1L);
               }
            }
         }
      }

      double ratio = totalVoxels > 0 ? (double) occupied / totalVoxels : 0.0;
      int[] minBox = (occupied > 0) ? new int[] {minX, minY, minZ} : new int[] {0, 0, 0};
      int[] maxBox = (occupied > 0) ? new int[] {maxX, maxY, maxZ} : new int[] {0, 0, 0};
      int[] dims = (occupied > 0) ? new int[] {maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1} : new int[] {0, 0, 0};

      return new VoxelDiagnostics(totalVoxels, occupied, ratio, minBox, maxBox, dims, histogram);
   }

   // ==========================================
   // 7. Multi-Frame RMSE Convergence Calculation
   // ==========================================

   public static double calculateExpectedRmse(float[] frameA, float[] frameB) {
      if (frameA.length != frameB.length || frameA.length == 0) {
         throw new IllegalArgumentException("Frames must have identical non-zero lengths");
      }
      double sumSq = 0.0;
      for (int i = 0; i < frameA.length; i++) {
         double delta = frameA[i] - frameB[i];
         sumSq += delta * delta;
      }
      return Math.sqrt(sumSq / frameA.length);
   }

   public static double calculateRmse(FloatBuffer bufA, FloatBuffer bufB, int floatCount) {
      double sumSq = 0.0;
      for (int i = 0; i < floatCount; i++) {
         double delta = bufA.get(i) - bufB.get(i);
         sumSq += delta * delta;
      }
      return Math.sqrt(sumSq / floatCount);
   }
}
