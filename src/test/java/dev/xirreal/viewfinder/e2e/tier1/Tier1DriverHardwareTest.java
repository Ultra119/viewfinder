package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.MetricsCollector;
import dev.xirreal.viewfinder.capture.ShaderLogParser;
import dev.xirreal.viewfinder.e2e.harness.E2ETestContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 (Feature Coverage): F3 - Driver Errors & Compute Profiling.
 * Isolated happy-path tests verifying OpenGL hardware limits, error capture routing,
 * GLSL compiler diagnostic parsing, compute dispatch timing, and diagnostics resets.
 */
public class Tier1DriverHardwareTest {

   @Test
   @DisplayName("F3.1: Driver info reflects compute and SSBO hardware limits")
   void testDriverInfoHardwareLimitsFieldsPresent() {
      JsonObject driver = E2ETestContext.createMockDriverInfo("NVIDIA Corporation", "NVIDIA RTX 4090", "4.6.0 NVIDIA 550.54", 1024, 49152, 2147483648L);

      assertEquals("NVIDIA Corporation", driver.get("vendor").getAsString());
      assertTrue(driver.get("programBinaryDumpSupported").getAsBoolean());
      assertEquals(1024, driver.get("maxComputeWorkGroupInvocations").getAsInt());
      assertEquals(49152, driver.get("maxComputeSharedMemorySize").getAsInt());
      assertEquals(2147483648L, driver.get("maxShaderStorageBlockSize").getAsLong());

      assertTrue(driver.has("maxComputeWorkGroupCount"));
      assertTrue(driver.has("maxComputeWorkGroupSize"));
      assertEquals(3, driver.getAsJsonArray("maxComputeWorkGroupSize").size());
   }

   @Test
   @DisplayName("F3.2: ErrorCapture routes OpenGL debug callback errors into structured list")
   void testErrorCaptureRoutingForDebugMessage() {
      ErrorCapture errorCapture = new ErrorCapture();
      errorCapture.addError("OpenGL Error", "composite_compute", "GL_INVALID_OPERATION in glDispatchCompute", "");

      List<ErrorCapture.ErrorEntry> entries = errorCapture.getErrors();
      assertEquals(1, entries.size());
      ErrorCapture.ErrorEntry entry = entries.get(0);
      assertEquals("OpenGL Error", entry.type);
      assertEquals("composite_compute", entry.filename);
      assertEquals("GL_INVALID_OPERATION in glDispatchCompute", entry.message);
      assertTrue(entry.timestamp > 0L);
   }

   @Test
   @DisplayName("F3.3: GLSL compiler error messages are parsed into structured file, line, and severity")
   void testCompilerErrorParsingInErrorCapture() {
      ErrorCapture errorCapture = new ErrorCapture();
      String rawLog = "0(15) : error C1008: undefined variable \"customImage\"";
      errorCapture.addError("Shader Compile Error", "setup.csh", rawLog, "");

      JsonArray json = errorCapture.toJson();
      assertEquals(1, json.size());
      JsonObject entry = json.get(0).getAsJsonObject();
      assertTrue(entry.has("compilerMessages"));

      JsonArray compilerMsgs = entry.getAsJsonArray("compilerMessages");
      assertEquals(1, compilerMsgs.size());
      JsonObject parsed = compilerMsgs.get(0).getAsJsonObject();
      assertEquals("error", parsed.get("severity").getAsString());
      assertEquals("0", parsed.get("file").getAsString());
      assertEquals(15, parsed.get("line").getAsInt());
      assertTrue(parsed.get("message").getAsString().contains("undefined variable"));
   }

   @Test
   @DisplayName("F3.4: MetricsCollector timing ring buffer records compute dispatch latency")
   void testMetricsCollectorComputeDispatchTiming() {
      MetricsCollector.TimingRingBuffer ring = new MetricsCollector.TimingRingBuffer(50);
      long sampleNs = 2_500_000L; // 2.5 ms
      ring.add(sampleNs);

      assertEquals(sampleNs, ring.getAverage());
      assertEquals(sampleNs, ring.getMin());
      assertEquals(sampleNs, ring.getMax());

      JsonObject json = ring.toJson(true);
      assertEquals(1, json.get("sampleCount").getAsInt());
      assertEquals(sampleNs, json.get("avg").getAsLong());
      assertTrue(json.has("samples"));
      assertEquals(sampleNs, json.getAsJsonArray("samples").get(0).getAsLong());
   }

   @Test
   @DisplayName("F3.5: Tool schema get_diagnostics validates includeSamples parameter")
   void testDriverInfoSchemaValidation() {
      Map<String, Object> args = Map.of("includeSamples", true);
      assertDoesNotThrow(() -> E2ETestContext.validateToolArguments("get_diagnostics", args, "diagnostics"));
   }

   @Test
   @DisplayName("F3.6: Clear diagnostics resets ErrorCapture state to 0 entries")
   void testClearDiagnosticsResetsState() {
      ErrorCapture errorCapture = new ErrorCapture();
      errorCapture.addError("TypeA", "fileA", "Msg1", "");
      errorCapture.addError("TypeB", "fileB", "Msg2", "");
      errorCapture.addError("TypeC", "fileC", "Msg3", "");
      assertEquals(3, errorCapture.getErrors().size());

      errorCapture.clearErrors();
      assertEquals(0, errorCapture.getErrors().size());
      assertEquals(0, errorCapture.toJson().size());
   }
}
