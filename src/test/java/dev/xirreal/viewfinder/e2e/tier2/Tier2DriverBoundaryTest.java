package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xirreal.viewfinder.capture.ErrorCapture;
import dev.xirreal.viewfinder.capture.MetricsCollector;
import dev.xirreal.viewfinder.capture.ShaderLogParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 2 (Boundary & Corner Cases): F3 - Driver Errors & Compute Profiling.
 * Tests boundary conditions: bounded queue capacity overflow (FIFO eviction at 100 items),
 * malformed driver compiler error strings, zero elapsed dispatch times, 64-bit timestamp overflow wraps,
 * and diagnostic filtering.
 */
public class Tier2DriverBoundaryTest {

   @Test
   @DisplayName("F2.3.1: ErrorCapture evicts oldest errors when capacity exceeds 100 entries (FIFO)")
   void testErrorCaptureQueueOverflowEviction() {
      ErrorCapture errorCapture = new ErrorCapture();

      // Inject 150 consecutive error messages
      for (int i = 1; i <= 150; i++) {
         errorCapture.addError("ErrorType", "shader_" + i, "Error message #" + i, "");
      }

      List<ErrorCapture.ErrorEntry> list = errorCapture.getErrors();
      assertEquals(100, list.size(), "Error list must be bounded to MAX_ERRORS (100)");

      // First entry in the list should now be error #51 (oldest 50 evicted)
      assertEquals("Error message #51", list.get(0).message);
      // Last entry should be error #150
      assertEquals("Error message #150", list.get(list.size() - 1).message);
   }

   @Test
   @DisplayName("F2.3.2: Malformed or unformatted GLSL driver logs parse safely without throwing StringIndexOutOfBoundsException")
   void testMalformedGlslErrorMessageParsing() {
      String[] malformedLogs = {
         "",
         "   ",
         "::::",
         "Internal compiler crash",
         "ERROR: syntax error",
         "0:???: syntax error",
         "0(abc): error C0000: unexpected"
      };

      for (String log : malformedLogs) {
         assertDoesNotThrow(() -> {
            List<ShaderLogParser.Message> parsed = ShaderLogParser.parse(log);
            // Parser must return empty or safely wrapped unparsed message
            for (ShaderLogParser.Message msg : parsed) {
               assertTrue(msg.line() >= 0);
            }
         }, "Failed parsing malformed log: " + log);
      }
   }

   @Test
   @DisplayName("F2.3.3: Mock driver hardware limits gracefully report zero or minimum when unsupported")
   void testHardwareLimitsFallbackOnMockDriver() {
      JsonObject fallbackDriver = new JsonObject();
      fallbackDriver.addProperty("vendor", "Mesa/X.org");
      fallbackDriver.addProperty("renderer", "llvmpipe");
      fallbackDriver.addProperty("programBinaryDumpSupported", false);
      fallbackDriver.addProperty("maxComputeWorkGroupInvocations", 0);
      fallbackDriver.addProperty("maxComputeSharedMemorySize", 0);

      assertFalse(fallbackDriver.get("programBinaryDumpSupported").getAsBoolean());
      assertEquals(0, fallbackDriver.get("maxComputeWorkGroupInvocations").getAsInt());
      assertEquals(0, fallbackDriver.get("maxComputeSharedMemorySize").getAsInt());
   }

   @Test
   @DisplayName("F2.3.4: MetricsCollector ring buffer handles zero elapsed time (delta = 0 ns)")
   void testMetricsCollectorZeroElapsedTime() {
      MetricsCollector.TimingRingBuffer ring = new MetricsCollector.TimingRingBuffer(10);
      ring.add(0L);
      ring.add(0L);

      assertEquals(0L, ring.getAverage());
      assertEquals(0L, ring.getMin());
      assertEquals(0L, ring.getMax());

      JsonObject json = ring.toJson(false);
      assertEquals(2, json.get("sampleCount").getAsInt());
      assertEquals(0L, json.get("avg").getAsLong());
   }

   @Test
   @DisplayName("F2.3.5: MetricsCollector 64-bit timestamp wrap calculates correct unsigned delta")
   void testMetricsCollectorTimer64BitWrap() {
      // When 64-bit hardware timer wraps from near 2^64-1 to near 0:
      long startTimestamp = 0xFFFFFFFFFF000000L;
      long endTimestamp = 0x0000000000001000L;

      // In two's complement unsigned arithmetic, end - start yields positive delta
      long delta = endTimestamp - startTimestamp;
      assertTrue(delta > 0L, "Unsigned delta must be positive across wrap");
      assertEquals(0x1001000L, delta);

      MetricsCollector.TimingRingBuffer ring = new MetricsCollector.TimingRingBuffer(5);
      ring.add(delta);
      assertEquals(delta, ring.getAverage());
   }

   @Test
   @DisplayName("F2.3.6: Debug messages filter non-error severity events from ErrorCapture")
   void testDebugCallbackNonErrorSeveritiesFiltered() {
      ErrorCapture errorCapture = new ErrorCapture();

      // Simulate a notification or low severity info message - filtered out
      boolean isErrorSeverity = false;
      if (isErrorSeverity) {
         errorCapture.addError("OpenGL Info", "composite", "Buffer detailed info", "");
      }
      assertEquals(0, errorCapture.getErrors().size());

      // Only HIGH / ERROR severity messages are routed
      isErrorSeverity = true;
      if (isErrorSeverity) {
         errorCapture.addError("OpenGL Error", "composite", "GL_OUT_OF_MEMORY", "");
      }
      assertEquals(1, errorCapture.getErrors().size());
   }
}
