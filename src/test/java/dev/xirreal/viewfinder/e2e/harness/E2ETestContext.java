package dev.xirreal.viewfinder.e2e.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end test context providing reflection bridges for MCP tool schemas,
 * mock pipeline programs, 3D texture descriptions, SSBO structures, and driver info snapshots.
 */
public final class E2ETestContext {

   private static final Class<?> TOOL_SCHEMAS_CLASS;
   private static final Method INPUT_METHOD;
   private static final Method VALIDATE_METHOD;

   static {
      try {
         TOOL_SCHEMAS_CLASS = Class.forName("dev.xirreal.viewfinder.mcp.ViewfinderToolSchemas");
         INPUT_METHOD = TOOL_SCHEMAS_CLASS.getDeclaredMethod("input", String.class);
         INPUT_METHOD.setAccessible(true);
         VALIDATE_METHOD = TOOL_SCHEMAS_CLASS.getDeclaredMethod("validate", Map.class, Object.class, String.class);
         VALIDATE_METHOD.setAccessible(true);
      } catch (Exception e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   private E2ETestContext() {}

   // ==========================================
   // 1. Tool Schema Reflection Bridge
   // ==========================================

   @SuppressWarnings("unchecked")
   public static Map<String, Object> getToolSchema(String toolName) {
      try {
         return (Map<String, Object>) INPUT_METHOD.invoke(null, toolName);
      } catch (Exception e) {
         throw new RuntimeException("Failed to invoke ViewfinderToolSchemas.input(\"" + toolName + "\")", e);
      }
   }

   public static void validateToolArguments(String toolName, Map<String, Object> arguments, String label) {
      Map<String, Object> schema = getToolSchema(toolName);
      try {
         VALIDATE_METHOD.invoke(null, schema, arguments, label);
      } catch (java.lang.reflect.InvocationTargetException e) {
         Throwable target = e.getTargetException();
         if (target instanceof RuntimeException re) throw re;
         throw new RuntimeException(target);
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   public static boolean isSchemaValidJsonSchema(Map<String, Object> schema) {
      return McpJsonDefaults.getSchemaValidator().validateSchema(schema).valid();
   }

   // ==========================================
   // 2. Program Reflection Models (Compute & Raster)
   // ==========================================

   public static JsonObject createMockComputeProgramReflection(
      int programId,
      String name,
      int localX,
      int localY,
      int localZ,
      List<MockDataBuffers.StructFieldDef> bufferVars
   ) {
      JsonObject prog = new JsonObject();
      prog.addProperty("programId", programId);
      prog.addProperty("name", name);
      prog.addProperty("linked", true);

      JsonObject compute = new JsonObject();
      JsonArray dims = new JsonArray();
      dims.add(localX);
      dims.add(localY);
      dims.add(localZ);
      compute.add("workGroupDimensions", dims);
      compute.addProperty("workGroupSizeX", localX);
      compute.addProperty("workGroupSizeY", localY);
      compute.addProperty("workGroupSizeZ", localZ);
      compute.addProperty("workGroupInvocations", (long) localX * localY * localZ);
      prog.add("compute", compute);

      if (bufferVars != null && !bufferVars.isEmpty()) {
         JsonArray vars = new JsonArray();
         for (MockDataBuffers.StructFieldDef field : bufferVars) {
            JsonObject varObj = new JsonObject();
            varObj.addProperty("name", field.name());
            varObj.addProperty("blockIndex", 0);
            varObj.addProperty("blockName", "StructuredBlock");
            varObj.addProperty("type", field.glType());
            varObj.addProperty("typeName", field.typeName());
            varObj.addProperty("offset", field.offset());
            varObj.addProperty("arraySize", 0);
            varObj.addProperty("arrayStride", field.arrayStride());
            varObj.addProperty("matrixStride", 0);
            vars.add(varObj);
         }
         prog.add("bufferVariables", vars);
      }

      return prog;
   }

   public static JsonObject createMockRasterProgramReflection(int programId, String name) {
      JsonObject prog = new JsonObject();
      prog.addProperty("programId", programId);
      prog.addProperty("name", name);
      prog.addProperty("linked", true);
      prog.add("compute", null);
      prog.add("bufferVariables", new JsonArray());
      return prog;
   }

   // ==========================================
   // 3. Texture Description Models (3D Voxel & 2D Radiance)
   // ==========================================

   public static JsonObject createMockTextureDescription(
      int textureId,
      String name,
      int target,
      int width,
      int height,
      int depth,
      int internalFormat,
      String formatName,
      boolean isInteger,
      boolean isSigned,
      int components
   ) {
      JsonObject tex = new JsonObject();
      tex.addProperty("textureId", textureId);
      if (name != null) tex.addProperty("name", name);
      tex.addProperty("target", target);
      tex.addProperty("width", width);
      tex.addProperty("height", height);
      tex.addProperty("depth", depth);
      tex.addProperty("internalFormat", internalFormat);
      tex.addProperty("formatName", formatName);
      tex.addProperty("integer", isInteger);
      tex.addProperty("signed", isSigned);
      tex.addProperty("components", components);
      return tex;
   }

   public static JsonObject createMockCustomImage(
      String name,
      String samplerName,
      int id,
      int width,
      int height,
      int depth,
      String format
   ) {
      JsonObject img = new JsonObject();
      img.addProperty("name", name);
      if (samplerName != null) img.addProperty("samplerName", samplerName);
      img.addProperty("id", id);
      img.addProperty("width", width);
      img.addProperty("height", height);
      img.addProperty("depth", depth);
      img.addProperty("format", format);
      return img;
   }

   // ==========================================
   // 4. Driver Info Snapshot Model
   // ==========================================

   public static JsonObject createMockDriverInfo(
      String vendor,
      String renderer,
      String version,
      int maxComputeInvocations,
      int maxSharedMem,
      long maxSsboSize
   ) {
      JsonObject driver = new JsonObject();
      driver.addProperty("vendor", vendor);
      driver.addProperty("renderer", renderer);
      driver.addProperty("openGlVersion", version);
      driver.addProperty("programBinaryDumpSupported", vendor != null && vendor.toLowerCase().contains("nvidia"));
      driver.addProperty("maxComputeWorkGroupInvocations", maxComputeInvocations);
      driver.addProperty("maxComputeSharedMemorySize", maxSharedMem);
      driver.addProperty("maxShaderStorageBlockSize", maxSsboSize);

      JsonArray maxCount = new JsonArray();
      maxCount.add(65535); maxCount.add(65535); maxCount.add(65535);
      driver.add("maxComputeWorkGroupCount", maxCount);

      JsonArray maxSize = new JsonArray();
      maxSize.add(1024); maxSize.add(1024); maxSize.add(64);
      driver.add("maxComputeWorkGroupSize", maxSize);

      return driver;
   }
}
