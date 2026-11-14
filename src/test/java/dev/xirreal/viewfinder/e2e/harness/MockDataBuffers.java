package dev.xirreal.viewfinder.e2e.harness;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/**
 * Synthetic direct and heap buffer allocators for simulating OpenGL textures,
 * 3D integer voxel grids, floating-point radiance buffers, and SSBO ByteBuffers.
 */
public final class MockDataBuffers {

   private MockDataBuffers() {}

   /**
    * Allocates a direct IntBuffer simulating a 3D integer voxel grid with given dimensions.
    * Coordinates are indexed as (z * sizeY * sizeX + y * sizeX + x) * components.
    */
   public static IntBuffer createVoxelGrid(int sizeX, int sizeY, int sizeZ, int components, Map<String, Integer> voxelMap) {
      int totalElements = sizeX * sizeY * sizeZ * components;
      IntBuffer buffer = ByteBuffer.allocateDirect(totalElements * Integer.BYTES)
         .order(ByteOrder.nativeOrder())
         .asIntBuffer();

      if (voxelMap != null) {
         for (Map.Entry<String, Integer> entry : voxelMap.entrySet()) {
            String[] parts = entry.getKey().split("[,:]");
            if (parts.length >= 3) {
               int x = Integer.parseInt(parts[0].trim());
               int y = Integer.parseInt(parts[1].trim());
               int z = Integer.parseInt(parts[2].trim());
               if (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ) {
                  int index = ((z * sizeY + y) * sizeX + x) * components;
                  buffer.put(index, entry.getValue());
               }
            }
         }
      }
      return buffer;
   }

   /**
    * Convenience overload for 1-component voxel grid.
    */
   public static IntBuffer createVoxelGrid(int sizeX, int sizeY, int sizeZ, Map<String, Integer> voxelMap) {
      return createVoxelGrid(sizeX, sizeY, sizeZ, 1, voxelMap);
   }

   /**
    * Allocates a direct FloatBuffer with HDR RGBA values (0.0 to 100.0+).
    */
   public static FloatBuffer createFloatRadianceBuffer(int width, int height, float[] pixelRgbValues) {
      int totalFloats = width * height * 4;
      FloatBuffer buffer = ByteBuffer.allocateDirect(totalFloats * Float.BYTES)
         .order(ByteOrder.nativeOrder())
         .asFloatBuffer();

      if (pixelRgbValues != null) {
         int limit = Math.min(pixelRgbValues.length, totalFloats);
         for (int i = 0; i < limit; i++) {
            buffer.put(i, pixelRgbValues[i]);
         }
      }
      return buffer;
   }

   /**
    * Allocates a direct FloatBuffer filled with a constant HDR color across all pixels.
    */
   public static FloatBuffer createUniformRadianceBuffer(int width, int height, float r, float g, float b, float a) {
      FloatBuffer buffer = ByteBuffer.allocateDirect(width * height * 4 * Float.BYTES)
         .order(ByteOrder.nativeOrder())
         .asFloatBuffer();
      for (int i = 0; i < width * height; i++) {
         int base = i * 4;
         buffer.put(base, r);
         buffer.put(base + 1, g);
         buffer.put(base + 2, b);
         buffer.put(base + 3, a);
      }
      return buffer;
   }

   /**
    * Allocates an SSBO ByteBuffer containing valid 32-bit floats, with NaNs and Infinities
    * bitwise injected at specified float indices.
    *
    * @param byteSize total byte capacity of the SSBO buffer
    * @param nanFloatIndices float indices where NaN (0x7FC00000 or signaling 0x7F800001) should be written
    * @param infFloatIndices float indices where positive/negative infinity should be written
    */
   public static ByteBuffer createSsboBufferWithNans(int byteSize, int[] nanFloatIndices, int[] infFloatIndices) {
      ByteBuffer buffer = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());
      int floatCount = byteSize / Float.BYTES;

      // Fill with valid sequential float values (e.g. 1.0, 2.0, ...)
      for (int i = 0; i < floatCount; i++) {
         buffer.putFloat(i * Float.BYTES, 1.0f + (i * 0.25f));
      }

      if (nanFloatIndices != null) {
         for (int idx : nanFloatIndices) {
            if (idx >= 0 && idx < floatCount) {
               // Alternate between quiet NaN (0x7FC00000) and signaling NaN (0x7F800001)
               int nanBits = (idx % 2 == 0) ? 0x7FC00000 : 0x7F800001;
               buffer.putInt(idx * Float.BYTES, nanBits);
            }
         }
      }

      if (infFloatIndices != null) {
         for (int idx : infFloatIndices) {
            if (idx >= 0 && idx < floatCount) {
               // Alternate between +Inf (0x7F800000) and -Inf (0xFF800000)
               int infBits = (idx % 2 == 0) ? 0x7F800000 : 0xFF800000;
               buffer.putInt(idx * Float.BYTES, infBits);
            }
         }
      }

      buffer.rewind();
      return buffer;
   }

   /**
    * Represents an std430 struct member variable description for SSBO layout tests.
    */
   public record StructFieldDef(
      String name,
      String typeName,
      int glType,
      int offset,
      int sizeBytes,
      int arrayStride
   ) {}

   /**
    * Creates an SSBO buffer packed with std430 struct instances according to member definitions.
    */
   public static ByteBuffer createStructBuffer(int byteSize, List<StructFieldDef> fields, Map<String, Object> values) {
      ByteBuffer buffer = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder());
      if (fields == null || values == null) return buffer;

      for (StructFieldDef field : fields) {
         Object val = values.get(field.name());
         if (val == null) continue;
         int offset = field.offset();
         if (offset + field.sizeBytes() > byteSize) continue;

         if (val instanceof Integer intVal) {
            buffer.putInt(offset, intVal);
         } else if (val instanceof Float floatVal) {
            buffer.putFloat(offset, floatVal);
         } else if (val instanceof float[] floatArray) {
            for (int i = 0; i < floatArray.length && (offset + (i + 1) * Float.BYTES) <= byteSize; i++) {
               buffer.putFloat(offset + i * Float.BYTES, floatArray[i]);
            }
         } else if (val instanceof int[] intArray) {
            for (int i = 0; i < intArray.length && (offset + (i + 1) * Integer.BYTES) <= byteSize; i++) {
               buffer.putInt(offset + i * Integer.BYTES, intArray[i]);
            }
         }
      }
      buffer.rewind();
      return buffer;
   }
}
