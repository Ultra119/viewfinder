package dev.xirreal.viewfinder.capture;

import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.Viewfinder;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public class TextureDumper {

   private static final int[] INTEGER_FORMATS = {
      GL43C.GL_R8I,
      GL43C.GL_RG8I,
      GL43C.GL_RGB8I,
      GL43C.GL_RGBA8I,
      GL43C.GL_R8UI,
      GL43C.GL_RG8UI,
      GL43C.GL_RGB8UI,
      GL43C.GL_RGBA8UI,
      GL43C.GL_R16I,
      GL43C.GL_RG16I,
      GL43C.GL_RGB16I,
      GL43C.GL_RGBA16I,
      GL43C.GL_R16UI,
      GL43C.GL_RG16UI,
      GL43C.GL_RGB16UI,
      GL43C.GL_RGBA16UI,
      GL43C.GL_R32I,
      GL43C.GL_RG32I,
      GL43C.GL_RGB32I,
      GL43C.GL_RGBA32I,
      GL43C.GL_R32UI,
      GL43C.GL_RG32UI,
      GL43C.GL_RGB32UI,
      GL43C.GL_RGBA32UI,
   };

   public static final int[] TURBO_LUT = generateTurboLut();
   public static final int[] VIRIDIS_LUT = generateViridisLut();

   public static class DumpOptions {
      public Integer slice = null;
      public String axis = "Z";
      public String format = "png"; // "png", "hdr", "raw"
      public String toneMap = "none"; // "none", "exposure"
      public float exposure = 1.0f;
      public boolean vectorRemap = false;
      public String heatmap = "none"; // "none", "turbo", "viridis"
      public float maxHeatmapSteps = 64.0f;
      public boolean nanMask = false;
      public boolean palette = false;

      public DumpOptions() {}

      public DumpOptions(
         Integer slice,
         String axis,
         String format,
         String toneMap,
         float exposure,
         boolean vectorRemap,
         String heatmap,
         float maxHeatmapSteps,
         boolean nanMask,
         boolean palette
      ) {
         this.slice = slice;
         this.axis = (axis != null && !axis.isBlank()) ? axis.toUpperCase() : "Z";
         this.format = (format != null && !format.isBlank()) ? format.toLowerCase() : "png";
         this.toneMap = (toneMap != null) ? toneMap.toLowerCase() : "none";
         this.exposure = exposure > 0.0f ? exposure : 1.0f;
         this.vectorRemap = vectorRemap;
         this.heatmap = (heatmap != null) ? heatmap.toLowerCase() : "none";
         this.maxHeatmapSteps = maxHeatmapSteps > 0.0f ? maxHeatmapSteps : 64.0f;
         this.nanMask = nanMask;
         this.palette = palette;
      }
   }

   public static boolean isIntegerFormat(int internalFormat) {
      for (int fmt : INTEGER_FORMATS) {
         if (fmt == internalFormat) return true;
      }
      return false;
   }

   public static boolean isSignedFormat(int internalFormat) {
      return (
         internalFormat == GL43C.GL_R8I ||
         internalFormat == GL43C.GL_RG8I ||
         internalFormat == GL43C.GL_RGB8I ||
         internalFormat == GL43C.GL_RGBA8I ||
         internalFormat == GL43C.GL_R16I ||
         internalFormat == GL43C.GL_RG16I ||
         internalFormat == GL43C.GL_RGB16I ||
         internalFormat == GL43C.GL_RGBA16I ||
         internalFormat == GL43C.GL_R32I ||
         internalFormat == GL43C.GL_RG32I ||
         internalFormat == GL43C.GL_RGB32I ||
         internalFormat == GL43C.GL_RGBA32I
      );
   }

   public static int getComponentCount(int internalFormat) {
      String name = getFormatName(internalFormat);
      if ("R11F_G11F_B10F".equals(name)) return 3;
      if ("RGB10_A2".equals(name)) return 4;
      if (name.startsWith("RGBA") || name.startsWith("BGRA")) return 4;
      if (name.startsWith("RGB") || name.startsWith("BGR")) return 3;
      if (name.startsWith("RG")) return 2;
      if (name.startsWith("R") || name.startsWith("RED")) return 1;
      return 4;
   }

   public static int getBytesPerPixel(int format, int type) {
      int components = switch (format) {
         case GL11C.GL_RED, GL30C.GL_RED_INTEGER -> 1;
         case GL30C.GL_RG, GL30C.GL_RG_INTEGER -> 2;
         case GL11C.GL_RGB, GL30C.GL_RGB_INTEGER -> 3;
         default -> 4;
      };
      int bytesPerComp = switch (type) {
         case GL11C.GL_BYTE, GL11C.GL_UNSIGNED_BYTE -> 1;
         case GL11C.GL_SHORT, GL11C.GL_UNSIGNED_SHORT -> 2;
         default -> 4;
      };
      return components * bytesPerComp;
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      ByteBuffer buffer
   ) {
      GLCapabilities caps = GL.getCapabilities();
      if (caps.OpenGL45 || caps.GL_ARB_get_texture_sub_image) {
         GL45C.glGetTextureSubImage(textureId, level, x, y, z, width, height, depth, format, type, buffer);
         return;
      }

      int prevFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
      int scratchFbo = GL30C.glGenFramebuffers();
      try {
         GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, scratchFbo);
         GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
         int bytesPerPixel = getBytesPerPixel(format, type);
         int sliceStride = width * height * bytesPerPixel;

         for (int slice = 0; slice < depth; slice++) {
            buffer.position(slice * sliceStride);
            if (target == GL12C.GL_TEXTURE_3D) {
               GL30C.glFramebufferTextureLayer(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, textureId, level, z + slice);
            } else {
               GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, textureId, level);
            }
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
               throw new IllegalStateException("Scratch FBO incomplete: 0x" + Integer.toHexString(status));
            }
            GL11C.glReadPixels(x, y, width, height, format, type, buffer);
         }
      } finally {
         buffer.clear();
         GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevFbo);
         GL30C.glDeleteFramebuffers(scratchFbo);
      }
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      IntBuffer buffer
   ) {
      GLCapabilities caps = GL.getCapabilities();
      if (caps.OpenGL45 || caps.GL_ARB_get_texture_sub_image) {
         GL45C.glGetTextureSubImage(textureId, level, x, y, z, width, height, depth, format, type, buffer);
         return;
      }
      ByteBuffer byteBuf = MemoryUtil.memByteBuffer(buffer);
      readSubImage(textureId, target, level, x, y, z, width, height, depth, format, type, byteBuf);
   }

   public static void readSubImage(
      int textureId,
      int target,
      int level,
      int x,
      int y,
      int z,
      int width,
      int height,
      int depth,
      int format,
      int type,
      FloatBuffer buffer
   ) {
      GLCapabilities caps = GL.getCapabilities();
      if (caps.OpenGL45 || caps.GL_ARB_get_texture_sub_image) {
         GL45C.glGetTextureSubImage(textureId, level, x, y, z, width, height, depth, format, type, buffer);
         return;
      }
      ByteBuffer byteBuf = MemoryUtil.memByteBuffer(buffer);
      readSubImage(textureId, target, level, x, y, z, width, height, depth, format, type, byteBuf);
   }

   public static int voxelIdToArgb(int id) {
      if (id == 0) {
         return 0x00000000;
      }
      double rawHue = (id * 0.618033988749895) % 1.0;
      if (rawHue < 0.0) {
         rawHue += 1.0;
      }
      float hue = (float) rawHue;
      float sat = 0.75f + (float) ((id & 3) * 0.07f);
      float bri = 0.85f + (float) (((id >> 2) & 3) * 0.04f);
      int rgb = java.awt.Color.HSBtoRGB(hue, sat, bri);
      return 0xFF000000 | (rgb & 0x00FFFFFF);
   }

   private static int[] generateTurboLut() {
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

   private static int[] generateViridisLut() {
      int[][] knots = {
         {68, 1, 84},
         {72, 40, 120},
         {62, 74, 137},
         {49, 104, 142},
         {38, 130, 142},
         {31, 158, 137},
         {53, 183, 121},
         {109, 205, 89},
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
         lut[i] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
      }
      return lut;
   }

   public static int sampleHeatmap(String colormap, float value, float maxSteps) {
      float steps = maxSteps > 0.0f ? maxSteps : 64.0f;
      float t = Math.max(0.0f, Math.min(1.0f, value / steps));
      int lutIndex = Math.min(255, Math.max(0, (int) (t * 255.0f + 0.5f)));
      int rgb = "viridis".equalsIgnoreCase(colormap) ? VIRIDIS_LUT[lutIndex] : TURBO_LUT[lutIndex];
      return 0xFF000000 | (rgb & 0x00FFFFFF);
   }

   public static int renderFloatPixel(float r, float g, float b, float a, DumpOptions options) {
      if (options.nanMask) {
         if (Float.isNaN(r) || Float.isNaN(g) || Float.isNaN(b) || Float.isNaN(a)) {
            return 0xFFFF00FF; // Magenta (#FF00FF)
         }
         if (Float.isInfinite(r) || Float.isInfinite(g) || Float.isInfinite(b) || Float.isInfinite(a)) {
            float infVal = Float.isInfinite(r) ? r : Float.isInfinite(g) ? g : Float.isInfinite(b) ? b : a;
            return infVal > 0.0f ? 0xFF00FFFF : 0xFFFFFF00; // Cyan (#00FFFF) for +Inf, Yellow (#FFFF00) for -Inf
         }
      }

      if ("turbo".equalsIgnoreCase(options.heatmap) || "viridis".equalsIgnoreCase(options.heatmap)) {
         return sampleHeatmap(options.heatmap, r, options.maxHeatmapSteps);
      }

      if (options.vectorRemap) {
         int red = clamp((int) (((r + 1.0f) * 0.5f) * 255.0f));
         int green = clamp((int) (((g + 1.0f) * 0.5f) * 255.0f));
         int blue = clamp((int) (((b + 1.0f) * 0.5f) * 255.0f));
         int alpha = (a > 0.0f) ? clamp((int) (a * 255.0f)) : 255;
         return (alpha << 24) | (red << 16) | (green << 8) | blue;
      }

      if ("exposure".equalsIgnoreCase(options.toneMap)) {
         float exp = options.exposure > 0.0f ? options.exposure : 1.0f;
         float tmR = 1.0f - (float) Math.exp(-Math.max(0.0f, r) * exp);
         float tmG = 1.0f - (float) Math.exp(-Math.max(0.0f, g) * exp);
         float tmB = 1.0f - (float) Math.exp(-Math.max(0.0f, b) * exp);
         int red = clamp((int) (tmR * 255.0f));
         int green = clamp((int) (tmG * 255.0f));
         int blue = clamp((int) (tmB * 255.0f));
         int alpha = (a > 0.0f) ? clamp((int) (a * 255.0f)) : 255;
         return (alpha << 24) | (red << 16) | (green << 8) | blue;
      }

      int red = clamp((int) (r * 255.0f));
      int green = clamp((int) (g * 255.0f));
      int blue = clamp((int) (b * 255.0f));
      int alpha = clamp((int) (a * 255.0f));
      if (alpha == 0 && a == 0.0f) alpha = 255;
      return (alpha << 24) | (red << 16) | (green << 8) | blue;
   }

   public static void floatToRgbe(float r, float g, float b, byte[] rgbe, int offset) {
      float maxVal = Math.max(Math.max(r, g), b);
      if (maxVal < 1e-32f) {
         rgbe[offset] = 0;
         rgbe[offset + 1] = 0;
         rgbe[offset + 2] = 0;
         rgbe[offset + 3] = 0;
         return;
      }
      int exp = Math.getExponent(maxVal) + 1;
      float scale = (float) Math.scalb(1.0f, -exp) * 256.0f;
      rgbe[offset]     = (byte) Math.min(255, Math.max(0, (int) (r * scale)));
      rgbe[offset + 1] = (byte) Math.min(255, Math.max(0, (int) (g * scale)));
      rgbe[offset + 2] = (byte) Math.min(255, Math.max(0, (int) (b * scale)));
      rgbe[offset + 3] = (byte) Math.max(0, Math.min(255, exp + 128));
   }

   public static float[] rgbeToFloat(byte r, byte g, byte b, byte e) {
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

   public static void writeHdr(FloatBuffer buffer, int width, int height, String outputPath) throws Exception {
      File outputFile = new File(outputPath);
      outputFile.getParentFile().mkdirs();
      try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
         writeHdr(buffer, width, height, out);
      }
   }

   public static void writeHdr(FloatBuffer buffer, int width, int height, OutputStream out) throws Exception {
      String header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\nSOFTWARE=Viewfinder\n\n-Y " + height + " +X " + width + "\n";
      out.write(header.getBytes(StandardCharsets.US_ASCII));

      byte[][] scanlineChannels = new byte[4][width];
      byte[] rgbe = new byte[4];

      for (int y = 0; y < height; y++) {
         int srcY = height - 1 - y;
         for (int x = 0; x < width; x++) {
            int i = (srcY * width + x) * 4;
            float r = buffer.get(i);
            float g = buffer.get(i + 1);
            float b = buffer.get(i + 2);
            floatToRgbe(r, g, b, rgbe, 0);
            scanlineChannels[0][x] = rgbe[0];
            scanlineChannels[1][x] = rgbe[1];
            scanlineChannels[2][x] = rgbe[2];
            scanlineChannels[3][x] = rgbe[3];
         }
         writeHdrScanlineRle(out, scanlineChannels, width);
      }
      out.flush();
   }

   public static void writeHdrScanlineRle(OutputStream out, byte[][] scanlineChannels, int width) throws Exception {
      if (width < 8 || width > 32767) {
         for (int i = 0; i < width; i++) {
            out.write(scanlineChannels[0][i] & 0xFF);
            out.write(scanlineChannels[1][i] & 0xFF);
            out.write(scanlineChannels[2][i] & 0xFF);
            out.write(scanlineChannels[3][i] & 0xFF);
         }
         return;
      }

      out.write(2);
      out.write(2);
      out.write((width >> 8) & 0xFF);
      out.write(width & 0xFF);

      for (int ch = 0; ch < 4; ch++) {
         byte[] channel = scanlineChannels[ch];
         int cursor = 0;
         while (cursor < width) {
            int runEnd = cursor;
            while (runEnd < width && channel[runEnd] == channel[cursor] && (runEnd - cursor) < 127) {
               runEnd++;
            }
            int runLen = runEnd - cursor;
            if (runLen >= 3 || (runEnd == width && runLen >= 2)) {
               out.write(128 + runLen);
               out.write(channel[cursor] & 0xFF);
               cursor = runEnd;
            } else {
               int litEnd = cursor;
               while (litEnd < width && (litEnd - cursor) < 128) {
                  if (litEnd + 2 < width && channel[litEnd] == channel[litEnd + 1] && channel[litEnd] == channel[litEnd + 2]) {
                     break;
                  }
                  litEnd++;
               }
               int litLen = litEnd - cursor;
               out.write(litLen);
               out.write(channel, cursor, litLen);
               cursor = litEnd;
            }
         }
      }
   }

   public static JsonObject dumpTexture(int textureId, String outputPath) {
      return dumpTexture(textureId, outputPath, new DumpOptions());
   }

   public static JsonObject dumpTexture(int textureId, String outputPath, DumpOptions options) {
      JsonObject result = new JsonObject();

      int target = detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = (target == GL43C.GL_TEXTURE_3D) ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;

         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean isInteger = isIntegerFormat(internalFormat);

         result.addProperty("textureId", textureId);
         result.addProperty("width", width);
         result.addProperty("height", height);
         if (depth > 1) result.addProperty("depth", depth);
         result.addProperty("internalFormat", internalFormat);
         result.addProperty("formatName", getFormatName(internalFormat));
         result.addProperty("format", options.format);

         if ("raw".equalsIgnoreCase(options.format)) {
            return dumpTextureRaw(textureId, outputPath);
         }

         if (target == GL43C.GL_TEXTURE_3D && depth > 1) {
            String axis = (options.axis != null && !options.axis.isBlank()) ? options.axis.toUpperCase() : "Z";
            int numSlices = "X".equals(axis) ? width : "Y".equals(axis) ? height : depth;

            if (options.slice != null) {
               int slice = options.slice;
               if (slice < 0 || slice >= numSlices) {
                  result.addProperty("error", "Requested slice " + slice + " out of bounds [0, " + (numSlices - 1) + "] for axis " + axis);
                  return result;
               }
               dumpSingle3DSlice(textureId, target, width, height, depth, slice, axis, internalFormat, isInteger, options, outputPath);
               result.addProperty("success", true);
               result.addProperty("path", new File(outputPath).getAbsolutePath());
               result.addProperty("slice", slice);
               result.addProperty("axis", axis);
            } else {
               String ext = "hdr".equalsIgnoreCase(options.format) ? ".hdr" : ".png";
               for (int s = 0; s < numSlices; s++) {
                  String slicePath = outputPath.endsWith(ext)
                     ? outputPath.substring(0, outputPath.length() - ext.length()) + "_" + axis.toLowerCase() + s + ext
                     : outputPath + "_" + axis.toLowerCase() + s + ext;
                  dumpSingle3DSlice(textureId, target, width, height, depth, s, axis, internalFormat, isInteger, options, slicePath);
               }
               result.addProperty("success", true);
               result.addProperty("path", outputPath.replace(ext, "_" + axis.toLowerCase() + "*" + ext));
               result.addProperty("layers", numSlices);
               result.addProperty("axis", axis);
            }
         } else {
            dump2DSlice(textureId, target, width, height, internalFormat, isInteger, options, outputPath);
            result.addProperty("success", true);
            result.addProperty("path", new File(outputPath).getAbsolutePath());
         }
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to dump texture {}", textureId, e);
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   private static void dumpSingle3DSlice(
      int textureId,
      int target,
      int width,
      int height,
      int depth,
      int slice,
      String axis,
      int internalFormat,
      boolean isInteger,
      DumpOptions options,
      String outputPath
   ) throws Exception {
      int components = getComponentCount(internalFormat);
      int sliceWidth;
      int sliceHeight;
      int subX = 0, subY = 0, subZ = 0;
      int subW = 1, subH = 1, subD = 1;

      if ("X".equals(axis)) {
         sliceWidth = depth;
         sliceHeight = height;
         subX = slice;
         subY = 0;
         subZ = 0;
         subW = 1;
         subH = height;
         subD = depth;
      } else if ("Y".equals(axis)) {
         sliceWidth = width;
         sliceHeight = depth;
         subX = 0;
         subY = slice;
         subZ = 0;
         subW = width;
         subH = 1;
         subD = depth;
      } else { // "Z"
         sliceWidth = width;
         sliceHeight = height;
         subX = 0;
         subY = 0;
         subZ = slice;
         subW = width;
         subH = height;
         subD = 1;
      }

      if (isInteger) {
         boolean signed = isSignedFormat(internalFormat);
         int pixelFormat = integerPixelFormat(components);
         int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
         IntBuffer buffer = MemoryUtil.memAllocInt(subW * subH * subD * components);
         try {
            readSubImage(textureId, target, 0, subX, subY, subZ, subW, subH, subD, pixelFormat, pixelType, buffer);

            BufferedImage image = new BufferedImage(sliceWidth, sliceHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < sliceHeight; y++) {
               int srcY = sliceHeight - 1 - y;
               for (int x = 0; x < sliceWidth; x++) {
                  int bufOffset;
                  if ("X".equals(axis)) {
                     // buffer is subD layers of subH rows of 1 pixel: (x * subH + srcY)
                     bufOffset = (x * height + srcY) * components;
                  } else if ("Y".equals(axis)) {
                     // buffer is subD layers of 1 row of subW pixels: (srcY * subW + x)
                     bufOffset = (srcY * width + x) * components;
                  } else {
                     bufOffset = (srcY * width + x) * components;
                  }

                  int id = signed ? buffer.get(bufOffset) : (int) Integer.toUnsignedLong(buffer.get(bufOffset));
                  int argb;
                  if (options.palette || components == 1) {
                     argb = voxelIdToArgb(id);
                  } else {
                     int g = components >= 2 ? (signed ? buffer.get(bufOffset + 1) : (int) Integer.toUnsignedLong(buffer.get(bufOffset + 1))) : 0;
                     int b = components >= 3 ? (signed ? buffer.get(bufOffset + 2) : (int) Integer.toUnsignedLong(buffer.get(bufOffset + 2))) : 0;
                     int a = components >= 4 ? (signed ? buffer.get(bufOffset + 3) : (int) Integer.toUnsignedLong(buffer.get(bufOffset + 3))) : 255;
                     argb = (clamp(a) << 24) | (clamp(id) << 16) | (clamp(g) << 8) | clamp(b);
                  }
                  image.setRGB(x, y, argb);
               }
            }
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            ImageIO.write(image, "PNG", outputFile);
         } finally {
            MemoryUtil.memFree(buffer);
         }
      } else {
         FloatBuffer buffer = MemoryUtil.memAllocFloat(subW * subH * subD * 4);
         try {
            readSubImage(textureId, target, 0, subX, subY, subZ, subW, subH, subD, GL43C.GL_RGBA, GL43C.GL_FLOAT, buffer);

            if ("hdr".equalsIgnoreCase(options.format)) {
               // Prepare contiguous 2D float slice buffer for HDR export
               FloatBuffer sliceBuffer = MemoryUtil.memAllocFloat(sliceWidth * sliceHeight * 4);
               try {
                  for (int y = 0; y < sliceHeight; y++) {
                     int srcY = sliceHeight - 1 - y;
                     for (int x = 0; x < sliceWidth; x++) {
                        int bufOffset;
                        if ("X".equals(axis)) {
                           bufOffset = (x * height + srcY) * 4;
                        } else if ("Y".equals(axis)) {
                           bufOffset = (srcY * width + x) * 4;
                        } else {
                           bufOffset = (srcY * width + x) * 4;
                        }
                        int destOffset = (srcY * sliceWidth + x) * 4;
                        sliceBuffer.put(destOffset, buffer.get(bufOffset));
                        sliceBuffer.put(destOffset + 1, buffer.get(bufOffset + 1));
                        sliceBuffer.put(destOffset + 2, buffer.get(bufOffset + 2));
                        sliceBuffer.put(destOffset + 3, buffer.get(bufOffset + 3));
                     }
                  }
                  writeHdr(sliceBuffer, sliceWidth, sliceHeight, outputPath);
               } finally {
                  MemoryUtil.memFree(sliceBuffer);
               }
            } else {
               BufferedImage image = new BufferedImage(sliceWidth, sliceHeight, BufferedImage.TYPE_INT_ARGB);
               for (int y = 0; y < sliceHeight; y++) {
                  int srcY = sliceHeight - 1 - y;
                  for (int x = 0; x < sliceWidth; x++) {
                     int bufOffset;
                     if ("X".equals(axis)) {
                        bufOffset = (x * height + srcY) * 4;
                     } else if ("Y".equals(axis)) {
                        bufOffset = (srcY * width + x) * 4;
                     } else {
                        bufOffset = (srcY * width + x) * 4;
                     }
                     float r = buffer.get(bufOffset);
                     float g = buffer.get(bufOffset + 1);
                     float b = buffer.get(bufOffset + 2);
                     float a = buffer.get(bufOffset + 3);
                     int argb = renderFloatPixel(r, g, b, a, options);
                     image.setRGB(x, y, argb);
                  }
               }
               File outputFile = new File(outputPath);
               outputFile.getParentFile().mkdirs();
               ImageIO.write(image, "PNG", outputFile);
            }
         } finally {
            MemoryUtil.memFree(buffer);
         }
      }
   }

   private static void dump2DSlice(
      int textureId,
      int target,
      int width,
      int height,
      int internalFormat,
      boolean isInteger,
      DumpOptions options,
      String outputPath
   ) throws Exception {
      int components = getComponentCount(internalFormat);

      if (isInteger) {
         boolean signed = isSignedFormat(internalFormat);
         int pixelFormat = integerPixelFormat(components);
         int pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
         IntBuffer buffer = MemoryUtil.memAllocInt(width * height * components);
         try {
            readSubImage(textureId, target, 0, 0, 0, 0, width, height, 1, pixelFormat, pixelType, buffer);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
               int srcY = height - 1 - y;
               for (int x = 0; x < width; x++) {
                  int i = (srcY * width + x) * components;
                  int id = signed ? buffer.get(i) : (int) Integer.toUnsignedLong(buffer.get(i));
                  int argb;
                  if (options.palette || components == 1) {
                     argb = voxelIdToArgb(id);
                  } else {
                     int g = components >= 2 ? (signed ? buffer.get(i + 1) : (int) Integer.toUnsignedLong(buffer.get(i + 1))) : 0;
                     int b = components >= 3 ? (signed ? buffer.get(i + 2) : (int) Integer.toUnsignedLong(buffer.get(i + 2))) : 0;
                     int a = components >= 4 ? (signed ? buffer.get(i + 3) : (int) Integer.toUnsignedLong(buffer.get(i + 3))) : 255;
                     argb = (clamp(a) << 24) | (clamp(id) << 16) | (clamp(g) << 8) | clamp(b);
                  }
                  image.setRGB(x, y, argb);
               }
            }
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            ImageIO.write(image, "PNG", outputFile);
         } finally {
            MemoryUtil.memFree(buffer);
         }
      } else {
         FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
         try {
            readSubImage(textureId, target, 0, 0, 0, 0, width, height, 1, GL43C.GL_RGBA, GL43C.GL_FLOAT, buffer);
            if ("hdr".equalsIgnoreCase(options.format)) {
               writeHdr(buffer, width, height, outputPath);
            } else {
               BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
               for (int y = 0; y < height; y++) {
                  int srcY = height - 1 - y;
                  for (int x = 0; x < width; x++) {
                     int i = (srcY * width + x) * 4;
                     float r = buffer.get(i);
                     float g = buffer.get(i + 1);
                     float b = buffer.get(i + 2);
                     float a = buffer.get(i + 3);
                     int argb = renderFloatPixel(r, g, b, a, options);
                     image.setRGB(x, y, argb);
                  }
               }
               File outputFile = new File(outputPath);
               outputFile.getParentFile().mkdirs();
               ImageIO.write(image, "PNG", outputFile);
            }
         } finally {
            MemoryUtil.memFree(buffer);
         }
      }
   }

   public static JsonObject dumpTextureRaw(int textureId, String outputPath) {
      JsonObject result = new JsonObject();

      int target = detectTarget(textureId);
      int previousTexture = GL43C.glGetInteger(getBindingQuery(target));
      try {
         GL43C.glBindTexture(target, textureId);

         int width = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_WIDTH);
         int height = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_HEIGHT);
         int depth = (target == GL43C.GL_TEXTURE_3D) ? GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_DEPTH) : 1;

         if (width <= 0 || height <= 0) {
            result.addProperty("error", "Texture " + textureId + " has invalid dimensions: " + width + "x" + height);
            return result;
         }

         int internalFormat = GL43C.glGetTexLevelParameteri(target, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
         boolean isInteger = isIntegerFormat(internalFormat);
         int components = getComponentCount(internalFormat);

         int pixelFormat, pixelType, bytesPerComponent;
         if (isInteger) {
            boolean signed = isSignedFormat(internalFormat);
            pixelFormat = integerPixelFormat(components);
            pixelType = signed ? GL43C.GL_INT : GL43C.GL_UNSIGNED_INT;
            bytesPerComponent = 4;
         } else {
            pixelFormat = (components == 1) ? GL43C.GL_RED : (components == 2) ? GL43C.GL_RG : (components == 3) ? GL43C.GL_RGB : GL43C.GL_RGBA;
            pixelType = GL43C.GL_FLOAT;
            bytesPerComponent = 4;
         }

         int totalPixels = width * height * depth;
         int bufferSize = totalPixels * components * bytesPerComponent;

         ByteBuffer buffer = MemoryUtil.memAlloc(bufferSize);
         try {
            readSubImage(textureId, target, 0, 0, 0, 0, width, height, depth, pixelFormat, pixelType, buffer);

            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outputFile); FileChannel channel = fos.getChannel()) {
               channel.write(buffer);
            }

            result.addProperty("success", true);
            result.addProperty("path", outputFile.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            if (depth > 1) result.addProperty("depth", depth);
            result.addProperty("components", components);
            result.addProperty("bytesPerComponent", bytesPerComponent);
            result.addProperty("pixelType", isInteger ? (isSignedFormat(internalFormat) ? "int" : "uint") : "float");
            result.addProperty("totalBytes", bufferSize);
            result.addProperty("internalFormat", internalFormat);
            result.addProperty("formatName", getFormatName(internalFormat));
            result.addProperty("textureId", textureId);
         } finally {
            MemoryUtil.memFree(buffer);
         }
      } catch (Exception e) {
         Viewfinder.LOGGER.error("Failed to dump raw texture {}", textureId, e);
         result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
         GL43C.glBindTexture(target, previousTexture);
      }

      return result;
   }

   public static void writeFloatSlice(FloatBuffer buffer, int width, int height, int layer, String outputPath) throws Exception {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      int sliceOffset = layer * width * height * 4;
      DumpOptions defaultOptions = new DumpOptions();
      for (int y = 0; y < height; y++) {
         int srcY = height - 1 - y;
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (srcY * width + x) * 4;
            float r = buffer.get(i);
            float g = buffer.get(i + 1);
            float b = buffer.get(i + 2);
            float a = buffer.get(i + 3);
            int argb = renderFloatPixel(r, g, b, a, defaultOptions);
            image.setRGB(x, y, argb);
         }
      }
      File outputFile = new File(outputPath);
      outputFile.getParentFile().mkdirs();
      ImageIO.write(image, "PNG", outputFile);
   }

   public static void writeIntegerSlice(
      IntBuffer buffer,
      int width,
      int height,
      int layer,
      int components,
      boolean signed,
      String outputPath
   ) throws Exception {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      int sliceOffset = layer * width * height * components;

      for (int y = 0; y < height; y++) {
         int srcY = height - 1 - y;
         for (int x = 0; x < width; x++) {
            int i = sliceOffset + (srcY * width + x) * components;
            int id = signed ? buffer.get(i) : (int) Integer.toUnsignedLong(buffer.get(i));
            image.setRGB(x, y, voxelIdToArgb(id));
         }
      }
      File outputFile = new File(outputPath);
      outputFile.getParentFile().mkdirs();
      ImageIO.write(image, "PNG", outputFile);
   }

   public static int detectTarget(int textureId) {
      int prev2D = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, textureId);
      int w = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prev2D);
      if (w > 0) return GL43C.GL_TEXTURE_2D;

      int prev3D = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_3D);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, textureId);
      w = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_3D, 0, GL43C.GL_TEXTURE_WIDTH);
      GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, prev3D);
      if (w > 0) return GL43C.GL_TEXTURE_3D;

      return GL43C.GL_TEXTURE_2D;
   }

   public static int getBindingQuery(int target) {
      if (target == GL43C.GL_TEXTURE_3D) return GL43C.GL_TEXTURE_BINDING_3D;
      return GL43C.GL_TEXTURE_BINDING_2D;
   }

   private static int clamp(int value) {
      return Math.max(0, Math.min(255, value));
   }

   public static int integerPixelFormat(int components) {
      return switch (components) {
         case 1 -> GL43C.GL_RED_INTEGER;
         case 2 -> GL43C.GL_RG_INTEGER;
         case 3 -> GL43C.GL_RGB_INTEGER;
         default -> GL43C.GL_RGBA_INTEGER;
      };
   }

   public static String getFormatName(int glFormat) {
      return switch (glFormat) {
         case GL43C.GL_RGBA8 -> "RGBA8";
         case GL43C.GL_RGB8 -> "RGB8";
         case GL43C.GL_R8 -> "R8";
         case GL43C.GL_RG8 -> "RG8";
         case GL43C.GL_R16F -> "R16F";
         case GL43C.GL_RG16F -> "RG16F";
         case GL43C.GL_RGB16F -> "RGB16F";
         case GL43C.GL_RGBA16F -> "RGBA16F";
         case GL43C.GL_R32F -> "R32F";
         case GL43C.GL_RG32F -> "RG32F";
         case GL43C.GL_RGB32F -> "RGB32F";
         case GL43C.GL_RGBA32F -> "RGBA32F";
         case GL43C.GL_R8I -> "R8I";
         case GL43C.GL_RG8I -> "RG8I";
         case GL43C.GL_RGB8I -> "RGB8I";
         case GL43C.GL_RGBA8I -> "RGBA8I";
         case GL43C.GL_R8UI -> "R8UI";
         case GL43C.GL_RG8UI -> "RG8UI";
         case GL43C.GL_RGB8UI -> "RGB8UI";
         case GL43C.GL_RGBA8UI -> "RGBA8UI";
         case GL43C.GL_R16I -> "R16I";
         case GL43C.GL_RG16I -> "RG16I";
         case GL43C.GL_RGB16I -> "RGB16I";
         case GL43C.GL_RGBA16I -> "RGBA16I";
         case GL43C.GL_R16UI -> "R16UI";
         case GL43C.GL_RG16UI -> "RG16UI";
         case GL43C.GL_RGB16UI -> "RGB16UI";
         case GL43C.GL_RGBA16UI -> "RGBA16UI";
         case GL43C.GL_R32I -> "R32I";
         case GL43C.GL_RG32I -> "RG32I";
         case GL43C.GL_RGB32I -> "RGB32I";
         case GL43C.GL_RGBA32I -> "RGBA32I";
         case GL43C.GL_R32UI -> "R32UI";
         case GL43C.GL_RG32UI -> "RG32UI";
         case GL43C.GL_RGB32UI -> "RGB32UI";
         case GL43C.GL_RGBA32UI -> "RGBA32UI";
         case GL43C.GL_R16 -> "R16";
         case GL43C.GL_RG16 -> "RG16";
         case GL43C.GL_RGB16 -> "RGB16";
         case GL43C.GL_RGBA16 -> "RGBA16";
         case GL43C.GL_R11F_G11F_B10F -> "R11F_G11F_B10F";
         case GL43C.GL_RGB10_A2 -> "RGB10_A2";
         default -> "0x" + Integer.toHexString(glFormat);
      };
   }
}
