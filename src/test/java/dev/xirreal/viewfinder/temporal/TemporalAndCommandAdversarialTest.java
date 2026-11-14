package dev.xirreal.viewfinder.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import dev.xirreal.viewfinder.e2e.harness.CommandTestHarness;
import dev.xirreal.viewfinder.mixin.client.MixinIrisTime;
import dev.xirreal.viewfinder.mixin.client.MixinLevelRenderer;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adversarial Unit and Boundary Test Suite for Temporal Controls, Mixins, and Commands.
 * Validates HistoryController fallback resilience, FreezeController caching and thread-safety,
 * ConvergenceProfiler precision and cancellation, Mixin cancellation contracts, and
 * ShaderDebugCommand real Brigadier tree parity.
 */
public class TemporalAndCommandAdversarialTest {

   private CommandDispatcher<FabricClientCommandSource> dispatcher;
   private CommandTestHarness.MockCommandSourceContext context;
   private FabricClientCommandSource source;

   @BeforeEach
   void setUp() {
      FreezeController.reset();
      context = new CommandTestHarness.MockCommandSourceContext();
      source = CommandTestHarness.createMockSource(context);
      // Create dispatcher with the real production ShaderDebugCommand tree (no mock shadowing)
      dispatcher = CommandTestHarness.createDispatcher();
   }

   @AfterEach
   void tearDown() {
      FreezeController.reset();
   }

   // ==========================================
   // 1. HistoryController Adversarial Probes
   // ==========================================

   @Test
   @DisplayName("History 1: flushHistory returns 0 safely when pipeline is inactive or null")
   void testFlushHistoryNullPipelineResilience() {
      int flushed = HistoryController.flushHistory();
      assertEquals(0, flushed, "flushHistory must return 0 without throwing when pipeline is inactive");
   }

   @Test
   @DisplayName("History 2: clearTexture returns immediately on zero or negative texture IDs")
   void testClearTextureInvalidIds() {
      HistoryController.clearTexture(0, GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 64, 64, 1);
      HistoryController.clearTexture(-1, GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 64, 64, 1);
      assertTrue(true, "Invalid texture IDs must return immediately without exceptions");
   }

   @Test
   @DisplayName("History 3: clearTexture catches headless GL context exceptions gracefully")
   void testClearTextureHeadlessGracefulCatch() {
      // In headless environment without GL context, clearTexture logs warning without crashing
      HistoryController.clearTexture(123, GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 128, 128, 1);
      HistoryController.clearTexture(123, GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 32, 32, 16);
      HistoryController.clearTexture(123, GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 32, 32, 0);
      assertTrue(true, "Headless invocation of clearTexture must not propagate unhandled exceptions");
   }

   // ==========================================
   // 2. FreezeController Adversarial Probes
   // ==========================================

   @Test
   @DisplayName("Freeze 1: Initial state and independent camera/jitter toggle controls")
   void testFreezeControllerBasics() {
      assertFalse(FreezeController.isCameraFrozen());
      assertFalse(FreezeController.isJitterFrozen());
      assertNull(FreezeController.getFrozenModelView());
      assertNull(FreezeController.getFrozenProjection());

      FreezeController.setCameraFrozen(true);
      assertTrue(FreezeController.isCameraFrozen());

      FreezeController.setJitterFrozen(true);
      assertTrue(FreezeController.isJitterFrozen());

      FreezeController.setCameraFrozen(false);
      assertFalse(FreezeController.isCameraFrozen());
      assertNull(FreezeController.getFrozenModelView());

      FreezeController.setJitterFrozen(false);
      assertFalse(FreezeController.isJitterFrozen());
   }

   @Test
   @DisplayName("Freeze 2: Matrix caching in captureIfUnset is idempotent and reset clears cache")
   void testFreezeControllerMatrixCaptureIdempotence() {
      FreezeController.setCameraFrozen(true);

      Matrix4f mat1 = new Matrix4f().scaling(2.0f);
      Matrix4f proj1 = new Matrix4f().translation(1.0f, 2.0f, 3.0f);

      FreezeController.captureIfUnset(mat1, proj1);

      Matrix4f cachedMv = FreezeController.getFrozenModelView();
      Matrix4f cachedProj = FreezeController.getFrozenProjection();
      assertNotNull(cachedMv);
      assertNotNull(cachedProj);
      assertEquals(mat1, cachedMv);
      assertEquals(proj1, cachedProj);

      // Attempt overwrite with different matrices
      Matrix4f mat2 = new Matrix4f().scaling(5.0f);
      Matrix4f proj2 = new Matrix4f().translation(10.0f, 20.0f, 30.0f);
      FreezeController.captureIfUnset(mat2, proj2);

      // Cached matrices must retain original snapshot
      assertEquals(mat1, FreezeController.getFrozenModelView());
      assertEquals(proj1, FreezeController.getFrozenProjection());

      // Reset must clear all state
      FreezeController.reset();
      assertFalse(FreezeController.isCameraFrozen());
      assertFalse(FreezeController.isJitterFrozen());
      assertNull(FreezeController.getFrozenModelView());
      assertNull(FreezeController.getFrozenProjection());
   }

   @Test
   @DisplayName("Freeze 3: Concurrent multi-threaded toggling is thread-safe without deadlocks")
   void testFreezeControllerConcurrency() throws InterruptedException {
      int threadCount = 16;
      CountDownLatch latch = new CountDownLatch(threadCount);
      AtomicInteger errors = new AtomicInteger(0);

      for (int i = 0; i < threadCount; i++) {
         final int threadId = i;
         new Thread(() -> {
            try {
               for (int j = 0; j < 500; j++) {
                  boolean freeze = (j % 2 == 0);
                  FreezeController.setCameraFrozen(freeze);
                  FreezeController.setJitterFrozen(freeze);
                  if (freeze) {
                     Matrix4f m = new Matrix4f().scaling(threadId + 1);
                     FreezeController.captureIfUnset(m, m);
                  }
                  FreezeController.isCameraFrozen();
                  FreezeController.isJitterFrozen();
                  FreezeController.getFrozenModelView();
               }
            } catch (Throwable t) {
               errors.incrementAndGet();
            } finally {
               latch.countDown();
            }
         }).start();
      }

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent threads timed out");
      assertEquals(0, errors.get(), "Thread safety violation detected in FreezeController");
   }

   // ==========================================
   // 3. ConvergenceProfiler Adversarial Probes
   // ==========================================

   @Test
   @DisplayName("Convergence 1: calculateRmse handles precision, uniform deltas, and large arrays")
   void testCalculateRmsePrecisionAndLargeBuffer() {
      // 1. Identical frames -> 0.0
      float[] fA = new float[1000];
      float[] fB = new float[1000];
      for (int i = 0; i < 1000; i++) {
         fA[i] = (float) Math.sin(i);
         fB[i] = fA[i];
      }
      assertEquals(0.0, ConvergenceProfiler.calculateRmse(fA, fB), 1e-9);

      // 2. Constant delta 0.75
      for (int i = 0; i < 1000; i++) {
         fB[i] = fA[i] + 0.75f;
      }
      assertEquals(0.75, ConvergenceProfiler.calculateRmse(fA, fB), 1e-6);

      // 3. Large buffer (1,000,000 floats) single outlier
      int largeSize = 1_000_000;
      float[] largeA = new float[largeSize];
      float[] largeB = new float[largeSize];
      largeB[42] = 1000.0f;
      // sqrt(1000^2 / 1,000,000) = 1.0
      assertEquals(1.0, ConvergenceProfiler.calculateRmse(largeA, largeB), 1e-9);
   }

   @Test
   @DisplayName("Convergence 2: calculateRmse validates null, empty, and mismatched arrays")
   void testCalculateRmseArrayValidation() {
      float[] valid = new float[10];
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(null, valid));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(valid, null));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(new float[0], new float[0]));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(new float[10], new float[20]));
   }

   @Test
   @DisplayName("Convergence 3: calculateRmse with FloatBuffer preserves buffer positions non-destructively")
   void testCalculateRmseFloatBuffer() {
      ByteBuffer bbA = ByteBuffer.allocateDirect(100 * Float.BYTES).order(ByteOrder.nativeOrder());
      ByteBuffer bbB = ByteBuffer.allocateDirect(100 * Float.BYTES).order(ByteOrder.nativeOrder());
      FloatBuffer fbA = bbA.asFloatBuffer();
      FloatBuffer fbB = bbB.asFloatBuffer();

      for (int i = 0; i < 100; i++) {
         fbA.put(i, 2.0f);
         fbB.put(i, 2.5f);
      }

      // Set position to 10
      fbA.position(10);
      fbB.position(10);

      double rmse = ConvergenceProfiler.calculateRmse(fbA, fbB, 50);
      assertEquals(0.5, rmse, 1e-6);

      // Verify position is unchanged
      assertEquals(10, fbA.position(), "Buffer A position must be preserved");
      assertEquals(10, fbB.position(), "Buffer B position must be preserved");

      // Validation exceptions
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(null, fbB, 10));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(fbA, null, 10));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(fbA, fbB, 0));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(fbA, fbB, -5));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.calculateRmse(fbA, fbB, 95));
   }

   @Test
   @DisplayName("Convergence 4: profileConvergence enforces parameter bounds [2, 600] and blocks re-entrancy")
   void testConvergenceProfilerLifecycleBounds() {
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.profileConvergence(1));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.profileConvergence(0));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.profileConvergence(-10));
      assertThrows(IllegalArgumentException.class, () -> ConvergenceProfiler.profileConvergence(601));

      CompletableFuture<ConvergenceProfiler.ConvergenceResult> future = ConvergenceProfiler.profileConvergence(10);
      assertNotNull(future);
      assertFalse(future.isDone());

      // Re-entrant call while active must throw IllegalStateException
      assertThrows(IllegalStateException.class, () -> ConvergenceProfiler.profileConvergence(5));

      // Cancel future
      future.cancel(true);
      assertTrue(future.isDone());

      // Starting new profile after cancellation must succeed
      CompletableFuture<ConvergenceProfiler.ConvergenceResult> future2 = ConvergenceProfiler.profileConvergence(5);
      assertNotNull(future2);
      future2.cancel(true);
   }

   @Test
   @DisplayName("Convergence 5: Multi-frame lifecycle evaluates convergence direction accurately")
   void testConvergenceProfilerLifecycleExecution() throws Exception {
      int frames = 5;
      CompletableFuture<ConvergenceProfiler.ConvergenceResult> future = ConvergenceProfiler.profileConvergence(frames);

      int pixelCount = 100;
      // Feed 5 frames with decreasing delta
      for (int f = 0; f < frames; f++) {
         float[] pixels = new float[pixelCount];
         float val = (float) Math.exp(-f * 0.5);
         for (int i = 0; i < pixelCount; i++) pixels[i] = val;
         ConvergenceProfiler.onFrameRendered(pixels);
      }

      assertTrue(future.isDone(), "Profile must complete when targetFrames is reached");
      ConvergenceProfiler.ConvergenceResult res = future.get();
      assertEquals(5, res.framesProfiled());
      assertEquals(4, res.deltaRmseHistory().size());
      assertTrue(res.isConverging(), "Result must indicate convergence for decaying delta");
      assertTrue(res.finalRmse() < res.initialRmse());
   }

   @Test
   @DisplayName("Convergence 6: onFrameRendered safely handles null frame data")
   void testConvergenceProfilerNullFrames() throws Exception {
      CompletableFuture<ConvergenceProfiler.ConvergenceResult> future = ConvergenceProfiler.profileConvergence(3);
      ConvergenceProfiler.onFrameRendered(null);
      ConvergenceProfiler.onFrameRendered(null);
      ConvergenceProfiler.onFrameRendered(null);

      assertTrue(future.isDone());
      ConvergenceProfiler.ConvergenceResult res = future.get();
      assertEquals(3, res.framesProfiled());
      assertEquals(0, res.deltaRmseHistory().size());
      assertEquals(0.0, res.initialRmse());
      assertEquals(0.0, res.finalRmse());
      assertFalse(res.isConverging());
   }

   // ==========================================
   // 4. Mixin Bytecode & Contract Probes
   // ==========================================

   @Test
   @DisplayName("Mixin 1: MixinIrisTime cancels beginFrame when jitter is frozen")
   void testMixinIrisTimeJitterCancellation() throws Exception {
      MixinIrisTime mixin = new MixinIrisTime();
      Method method = MixinIrisTime.class.getDeclaredMethod("viewfinder$onBeginFrame", CallbackInfo.class);
      method.setAccessible(true);

      // Jitter NOT frozen
      FreezeController.setJitterFrozen(false);
      CallbackInfo ci1 = new CallbackInfo("beginFrame", true);
      method.invoke(mixin, ci1);
      assertFalse(ci1.isCancelled(), "beginFrame must not be cancelled when jitter is unfrozen");

      // Jitter FROZEN
      FreezeController.setJitterFrozen(true);
      CallbackInfo ci2 = new CallbackInfo("beginFrame", true);
      method.invoke(mixin, ci2);
      assertTrue(ci2.isCancelled(), "beginFrame must be cancelled when jitter is frozen");
   }

   @Test
   @DisplayName("Mixin 2: MixinLevelRenderer cancels and captures camera matrices when camera is frozen")
   void testMixinLevelRendererCameraCancellation() throws Exception {
      MixinLevelRenderer mixin = new MixinLevelRenderer();
      Method mvMethod = MixinLevelRenderer.class.getDeclaredMethod("viewfinder$freezeModelView", Matrix4fc.class, CallbackInfo.class);
      mvMethod.setAccessible(true);
      Method projMethod = MixinLevelRenderer.class.getDeclaredMethod("viewfinder$freezeProjection", Matrix4f.class, CallbackInfo.class);
      projMethod.setAccessible(true);

      Matrix4f modelView = new Matrix4f().scaling(3.0f);
      Matrix4f proj = new Matrix4f().translation(4.0f, 5.0f, 6.0f);

      // Camera NOT frozen
      FreezeController.setCameraFrozen(false);
      CallbackInfo ci1 = new CallbackInfo("setGbufferModelView", true);
      mvMethod.invoke(mixin, modelView, ci1);
      assertFalse(ci1.isCancelled(), "setGbufferModelView must not be cancelled when camera is unfrozen");

      CallbackInfo ci2 = new CallbackInfo("setGbufferProjection", true);
      projMethod.invoke(mixin, proj, ci2);
      assertFalse(ci2.isCancelled(), "setGbufferProjection must not be cancelled when camera is unfrozen");

      // Camera FROZEN
      FreezeController.setCameraFrozen(true);
      CallbackInfo ci3 = new CallbackInfo("setGbufferModelView", true);
      mvMethod.invoke(mixin, modelView, ci3);
      assertTrue(ci3.isCancelled(), "setGbufferModelView must be cancelled when camera is frozen");
      assertEquals(modelView, FreezeController.getFrozenModelView(), "Frozen model-view matrix must be captured");

      CallbackInfo ci4 = new CallbackInfo("setGbufferProjection", true);
      projMethod.invoke(mixin, proj, ci4);
      assertTrue(ci4.isCancelled(), "setGbufferProjection must be cancelled when camera is frozen");
      assertEquals(proj, FreezeController.getFrozenProjection(), "Frozen projection matrix must be captured");
   }

   // ==========================================
   // 5. ShaderDebugCommand Real Dispatcher Probes
   // ==========================================

   @Test
   @DisplayName("Command 1: Real ShaderDebugCommand /viewfinder freeze camera/jitter/all subcommands")
   void testRealCommandFreezeControls() throws Exception {
      int res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze status");
      assertEquals(1, res);

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera true");
      assertEquals(1, res);
      assertTrue(FreezeController.isCameraFrozen());

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera false");
      assertEquals(1, res);
      assertFalse(FreezeController.isCameraFrozen());

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze jitter true");
      assertEquals(1, res);
      assertTrue(FreezeController.isJitterFrozen());

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze jitter false");
      assertEquals(1, res);
      assertFalse(FreezeController.isJitterFrozen());

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze all true");
      assertEquals(1, res);
      assertTrue(FreezeController.isCameraFrozen());
      assertTrue(FreezeController.isJitterFrozen());

      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze all false");
      assertEquals(1, res);
      assertFalse(FreezeController.isCameraFrozen());
      assertFalse(FreezeController.isJitterFrozen());
   }

   @Test
   @DisplayName("Command 2: Real ShaderDebugCommand /viewfinder history flush execution")
   void testRealCommandHistoryFlush() throws Exception {
      int res = CommandTestHarness.execute(dispatcher, source, "/viewfinder history flush");
      assertEquals(1, res);
   }

   @Test
   @DisplayName("Command 3: Real ShaderDebugCommand /viewfinder convergence parameters and boundaries")
   void testRealCommandConvergenceBoundaries() throws Exception {
      // Default 30 frames
      int res = CommandTestHarness.execute(dispatcher, source, "/viewfinder convergence");
      assertEquals(1, res);

      // Complete profile frames to clear active state
      for (int i = 0; i < 35; i++) ConvergenceProfiler.onFrameRendered(new float[10]);

      // Custom 10 frames
      res = CommandTestHarness.execute(dispatcher, source, "/viewfinder convergence 10");
      assertEquals(1, res);
      for (int i = 0; i < 15; i++) ConvergenceProfiler.onFrameRendered(new float[10]);

      // Boundary: frames = 1 (fails parse: min is 2)
      ParseResults<FabricClientCommandSource> parse1 = CommandTestHarness.parse(dispatcher, source, "/viewfinder convergence 1");
      assertTrue(parse1.getReader().canRead() || !parse1.getExceptions().isEmpty(), "Frames=1 must be rejected by Brigadier");

      // Boundary: frames = 601 (fails parse: max is 600)
      ParseResults<FabricClientCommandSource> parse601 = CommandTestHarness.parse(dispatcher, source, "/viewfinder convergence 601");
      assertTrue(parse601.getReader().canRead() || !parse601.getExceptions().isEmpty(), "Frames=601 must be rejected by Brigadier");
   }

   @Test
   @DisplayName("Command 4: Real ShaderDebugCommand Brigadier argument boundaries across subcommands")
   void testRealCommandSubcommandBoundaries() {
      // /viewfinder ssbo scan index >= 0
      ParseResults<FabricClientCommandSource> pScanNeg = CommandTestHarness.parse(dispatcher, source, "/viewfinder ssbo scan -1");
      assertTrue(pScanNeg.getReader().canRead() || !pScanNeg.getExceptions().isEmpty());

      // /viewfinder ssbo inspect bytes >= 1
      ParseResults<FabricClientCommandSource> pInspectZeroBytes = CommandTestHarness.parse(dispatcher, source, "/viewfinder ssbo inspect 0 0");
      assertTrue(pInspectZeroBytes.getReader().canRead() || !pInspectZeroBytes.getExceptions().isEmpty());

      // /viewfinder ssbo inspect offset >= 0
      ParseResults<FabricClientCommandSource> pInspectNegOffset = CommandTestHarness.parse(dispatcher, source, "/viewfinder ssbo inspect 0 10 -1");
      assertTrue(pInspectNegOffset.getReader().canRead() || !pInspectNegOffset.getExceptions().isEmpty());

      // /viewfinder voxel id >= 1
      ParseResults<FabricClientCommandSource> pVoxelZeroId = CommandTestHarness.parse(dispatcher, source, "/viewfinder voxel id 0");
      assertTrue(pVoxelZeroId.getReader().canRead() || !pVoxelZeroId.getExceptions().isEmpty());

      // /viewfinder compute profile frames [1, 600]
      ParseResults<FabricClientCommandSource> pProfileZero = CommandTestHarness.parse(dispatcher, source, "/viewfinder compute profile 0");
      assertTrue(pProfileZero.getReader().canRead() || !pProfileZero.getExceptions().isEmpty());

      ParseResults<FabricClientCommandSource> pProfile601 = CommandTestHarness.parse(dispatcher, source, "/viewfinder compute profile 601");
      assertTrue(pProfile601.getReader().canRead() || !pProfile601.getExceptions().isEmpty());
   }
}
