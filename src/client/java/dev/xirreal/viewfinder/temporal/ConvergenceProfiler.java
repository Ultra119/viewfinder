package dev.xirreal.viewfinder.temporal;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ConvergenceProfiler {

   public record ConvergenceResult(
      int framesProfiled,
      double initialRmse,
      double finalRmse,
      List<Double> deltaRmseHistory,
      boolean isConverging
   ) {}

   public static double calculateRmse(float[] frameA, float[] frameB) {
      if (frameA == null || frameB == null || frameA.length != frameB.length || frameA.length == 0) {
         throw new IllegalArgumentException("Frames must be non-null and have identical non-zero lengths");
      }
      double sumSq = 0.0;
      for (int i = 0; i < frameA.length; i++) {
         double delta = frameA[i] - frameB[i];
         sumSq += delta * delta;
      }
      return Math.sqrt(sumSq / frameA.length);
   }

   public static double calculateRmse(FloatBuffer bufA, FloatBuffer bufB, int floatCount) {
      if (bufA == null || bufB == null || bufA.remaining() < floatCount || bufB.remaining() < floatCount || floatCount <= 0) {
         throw new IllegalArgumentException("Float buffers must be non-null and have at least floatCount elements");
      }
      double sumSq = 0.0;
      int posA = bufA.position();
      int posB = bufB.position();
      for (int i = 0; i < floatCount; i++) {
         double delta = bufA.get(posA + i) - bufB.get(posB + i);
         sumSq += delta * delta;
      }
      return Math.sqrt(sumSq / floatCount);
   }

   private static volatile CompletableFuture<ConvergenceResult> activeProfile = null;
   private static volatile int targetFrames = 0;
   private static volatile int framesCollected = 0;
   private static float[] previousFrameData = null;
   private static final List<Double> deltaHistory = new ArrayList<>();

   public static synchronized CompletableFuture<ConvergenceResult> profileConvergence(int frames) {
      if (frames < 2 || frames > 600) {
         throw new IllegalArgumentException("frames must be between 2 and 600");
      }
      if (activeProfile != null && !activeProfile.isDone()) {
         throw new IllegalStateException("A convergence profile is already in progress");
      }

      targetFrames = frames;
      framesCollected = 0;
      previousFrameData = null;
      deltaHistory.clear();
      activeProfile = new CompletableFuture<>();
      return activeProfile;
   }

   public static CompletableFuture<ConvergenceResult> measureConvergence(int frames) {
      return profileConvergence(frames);
   }

   public static synchronized void onFrameRendered(float[] currentFramePixels) {
      if (activeProfile == null || activeProfile.isDone()) return;

      framesCollected++;
      if (previousFrameData != null && currentFramePixels != null) {
         if (previousFrameData.length == currentFramePixels.length && currentFramePixels.length > 0) {
            double deltaRmse = calculateRmse(previousFrameData, currentFramePixels);
            deltaHistory.add(deltaRmse);
         }
      }

      if (currentFramePixels != null) {
         if (previousFrameData == null || previousFrameData.length != currentFramePixels.length) {
            previousFrameData = new float[currentFramePixels.length];
         }
         System.arraycopy(currentFramePixels, 0, previousFrameData, 0, currentFramePixels.length);
      }

      if (framesCollected >= targetFrames) {
         double init = deltaHistory.size() > 0 ? deltaHistory.get(0) : 0.0;
         double fin = deltaHistory.size() > 0 ? deltaHistory.get(deltaHistory.size() - 1) : 0.0;
         ConvergenceResult res = new ConvergenceResult(targetFrames, init, fin, new ArrayList<>(deltaHistory), fin < init);
         CompletableFuture<ConvergenceResult> future = activeProfile;
         activeProfile = null;
         previousFrameData = null;
         future.complete(res);
      }
   }
}
