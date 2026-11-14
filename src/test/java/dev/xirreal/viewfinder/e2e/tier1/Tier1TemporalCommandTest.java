package dev.xirreal.viewfinder.e2e.tier1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import dev.xirreal.viewfinder.e2e.harness.AlgorithmVerifiers;
import dev.xirreal.viewfinder.e2e.harness.CommandTestHarness;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 (Feature Coverage): F6 - Temporal Controls & In-Game Command Parity.
 * Isolated happy-path tests verifying RMSE convergence calculation, camera and jitter freeze controls,
 * history flush execution, and Brigadier command parsing.
 */
public class Tier1TemporalCommandTest {

   private CommandDispatcher<FabricClientCommandSource> dispatcher;
   private CommandTestHarness.MockCommandSourceContext context;
   private FabricClientCommandSource source;

   @BeforeEach
   void setUp() {
      context = new CommandTestHarness.MockCommandSourceContext();
      source = CommandTestHarness.createMockSource(context);
      dispatcher = CommandTestHarness.createDispatcher();
      CommandTestHarness.registerExtendedR6Commands(dispatcher, context);
   }

   @Test
   @DisplayName("F6.1: Convergence RMSE calculation returns exactly 0.0 for identical frames")
   void testConvergenceRmseCalculationIdenticalFrames() {
      int size = 64 * 64 * 4;
      float[] frameA = new float[size];
      float[] frameB = new float[size];
      for (int i = 0; i < size; i++) {
         frameA[i] = (float) Math.sin(i * 0.1);
         frameB[i] = frameA[i];
      }

      double rmse = AlgorithmVerifiers.calculateExpectedRmse(frameA, frameB);
      assertEquals(0.0, rmse, 1e-9);
   }

   @Test
   @DisplayName("F6.2: Convergence RMSE calculation accurately evaluates known uniform delta")
   void testConvergenceRmseCalculationKnownNoise() {
      int size = 1000;
      float[] frameA = new float[size];
      float[] frameB = new float[size];
      for (int i = 0; i < size; i++) {
         frameA[i] = 1.0f;
         frameB[i] = 1.5f; // delta = 0.5
      }

      double rmse = AlgorithmVerifiers.calculateExpectedRmse(frameA, frameB);
      assertEquals(0.5, rmse, 1e-6);
   }

   @Test
   @DisplayName("F6.3: Command /viewfinder freeze camera sets camera frozen state")
   void testCameraFreezeToggleState() throws Exception {
      assertFalse(context.isCameraFrozen());

      int result = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera true");
      assertEquals(1, result);
      assertTrue(context.isCameraFrozen());

      result = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera false");
      assertEquals(1, result);
      assertFalse(context.isCameraFrozen());
   }

   @Test
   @DisplayName("F6.4: Command /viewfinder freeze jitter sets temporal jitter frozen state")
   void testJitterFreezeToggleState() throws Exception {
      assertFalse(context.isJitterFrozen());

      int result = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze jitter true");
      assertEquals(1, result);
      assertTrue(context.isJitterFrozen());

      result = CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze jitter false");
      assertEquals(1, result);
      assertFalse(context.isJitterFrozen());
   }

   @Test
   @DisplayName("F6.5: Command /viewfinder history flush triggers history clearing")
   void testHistoryFlushExecutionContract() throws Exception {
      assertEquals(0, context.getHistoryFlushedCount());

      int result = CommandTestHarness.execute(dispatcher, source, "/viewfinder history flush");
      assertEquals(1, result);
      assertEquals(1, context.getHistoryFlushedCount());
      assertTrue(context.getFeedback().stream().anyMatch(msg -> msg.contains("Flushed")));
   }

   @Test
   @DisplayName("F6.6: Brigadier command tree recognizes all /viewfinder subcommands")
   void testBrigadierCommandTreeRegistration() {
      String[] validCommands = {
         "/viewfinder status",
         "/viewfinder help",
         "/viewfinder history flush",
         "/viewfinder freeze camera true",
         "/viewfinder freeze jitter false",
         "/viewfinder freeze all true",
         "/viewfinder convergence 30",
         "/viewfinder compute list",
         "/viewfinder voxel name voxelGrid",
         "/viewfinder ssbo scan 0"
      };

      for (String cmd : validCommands) {
         ParseResults<FabricClientCommandSource> parse = CommandTestHarness.parse(dispatcher, source, cmd);
         assertFalse(parse.getReader().canRead(), "Command parsing failed for: " + cmd + " (remaining: '" + parse.getReader().getRemaining() + "')");
         assertTrue(parse.getExceptions().isEmpty(), "Unexpected parse exception for: " + cmd);
      }
   }
}
