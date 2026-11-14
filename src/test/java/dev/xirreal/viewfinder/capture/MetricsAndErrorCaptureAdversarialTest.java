package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adversarial test suite for MetricsCollector lifecycle, TimingRingBuffer wrapping,
 * ErrorCapture concurrency safety, and ShaderLogParser integer overflow resilience.
 */
class MetricsAndErrorCaptureAdversarialTest {

   @Test
   @DisplayName("MetricsCollector.captureFrames validates frame count bounds [1, 600]")
   void testCaptureFramesBoundsValidation() {
      MetricsCollector collector = new MetricsCollector();

      assertThrows(IllegalArgumentException.class, () -> collector.captureFrames(0));
      assertThrows(IllegalArgumentException.class, () -> collector.captureFrames(-5));
      assertThrows(IllegalArgumentException.class, () -> collector.captureFrames(601));
      assertThrows(IllegalArgumentException.class, () -> collector.captureFrames(1000));

      assertDoesNotThrow(() -> {
         CompletableFuture<JsonObject> f1 = collector.captureFrames(1);
         assertFalse(f1.isDone());
         collector.reset();
      });

      assertDoesNotThrow(() -> {
         CompletableFuture<JsonObject> f600 = collector.captureFrames(600);
         assertFalse(f600.isDone());
         collector.reset();
      });
   }

   @Test
   @DisplayName("Starting new capture while previous is active aborts previous future with exception")
   void testCaptureAbortsPreviousPendingFutureOnReset() {
      MetricsCollector collector = new MetricsCollector();
      CompletableFuture<JsonObject> first = collector.captureFrames(30);

      // Re-triggering capture calls reset() which must complete first exceptionally
      CompletableFuture<JsonObject> second = collector.captureFrames(10);

      assertTrue(first.isCompletedExceptionally());
      ExecutionException ex = assertThrows(ExecutionException.class, first::get);
      assertTrue(ex.getCause() instanceof IllegalStateException);
      assertTrue(ex.getCause().getMessage().contains("Profile capture was reset"));

      assertFalse(second.isDone());
      collector.reset();
   }

   @Test
   @DisplayName("Calling finishCapture completes pending capture future and returns captured frame count")
   void testFinishCaptureCompletesFuture() throws Exception {
      MetricsCollector collector = new MetricsCollector();
      CompletableFuture<JsonObject> future = collector.captureFrames(10);

      JsonObject summary = collector.finishCapture();
      assertEquals(0, summary.get("framesCaptured").getAsInt());
      assertTrue(future.isDone());
      assertFalse(future.isCompletedExceptionally());
      assertFalse(collector.isCapturing());
   }

   @Test
   @DisplayName("Unbalanced endTiming and endComputeTiming calls do not crash")
   void testUnbalancedTimingCallsAreSafe() {
      MetricsCollector collector = new MetricsCollector();
      // Calling end when nothing began
      assertDoesNotThrow(collector::endTiming);
      assertDoesNotThrow(collector::endComputeTiming);
   }

   @Test
   @DisplayName("TimingRingBuffer handles capacity 1 boundary condition correctly")
   void testTimingRingBufferCapacityOne() {
      MetricsCollector.TimingRingBuffer ring = new MetricsCollector.TimingRingBuffer(1);
      assertEquals(0, ring.getAverage());
      assertEquals(0, ring.getSamples().length);

      ring.add(500);
      assertEquals(500, ring.getAverage());
      assertEquals(500, ring.getMin());
      assertEquals(500, ring.getMax());
      assertEquals(500, ring.getLatest());
      assertEquals(1, ring.getSamples().length);
      assertEquals(500, ring.getSamples()[0]);

      ring.add(900);
      assertEquals(900, ring.getAverage());
      assertEquals(900, ring.getLatest());
      assertEquals(1, ring.getSamples().length);
      assertEquals(900, ring.getSamples()[0]);
   }

   @Test
   @DisplayName("ShaderLogParser handles integer overflow in line numbers safely without throwing")
   void testShaderLogParserLineNumberOverflowSafety() {
      // Line number exceeds Integer.MAX_VALUE (2147483647)
      String overflowColonLog = "ERROR: 0:99999999999999999999: syntax error";
      String overflowParenLog = "0(99999999999999999999) : error C1008: overflow";

      assertDoesNotThrow(() -> {
         List<ShaderLogParser.Message> msgs = ShaderLogParser.parse(overflowColonLog);
         for (ShaderLogParser.Message m : msgs) {
            assertNotNull(m.severity());
            assertEquals(-1, m.line());
         }
         List<ShaderLogParser.Message> msgsParen = ShaderLogParser.parse(overflowParenLog);
         for (ShaderLogParser.Message m : msgsParen) {
            assertNotNull(m.severity());
            assertEquals(-1, m.line());
         }
      });
   }

   @Test
   @DisplayName("ErrorCapture multithreaded addError and clearErrors concurrent execution")
   void testErrorCaptureConcurrentAddAndClear() throws Exception {
      ErrorCapture capture = new ErrorCapture();
      for (int i = 0; i < 100; i++) {
         capture.addError("Init", "file", "msg" + i, "");
      }

      AtomicBoolean running = new AtomicBoolean(true);
      AtomicBoolean raceCaught = new AtomicBoolean(false);

      Thread adder = new Thread(() -> {
         int counter = 0;
         while (running.get()) {
            try {
               capture.addError("Type", "file", "error_" + counter++, "");
            } catch (IndexOutOfBoundsException e) {
               raceCaught.set(true);
               break;
            }
         }
      });

      Thread clearer = new Thread(() -> {
         while (running.get()) {
            capture.clearErrors();
            Thread.yield();
         }
      });

      adder.start();
      clearer.start();
      Thread.sleep(200);
      running.set(false);
      adder.join();
      clearer.join();

      assertFalse(raceCaught.get(), "IndexOutOfBoundsException must never be thrown during concurrent addError/clearErrors");
   }
}
