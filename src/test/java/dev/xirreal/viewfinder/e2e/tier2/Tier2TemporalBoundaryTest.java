package dev.xirreal.viewfinder.e2e.tier2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Tier 2 (Boundary & Corner Cases): F6 - Temporal Controls & In-Game Command Parity.
 * Tests boundary conditions: single-pixel spike in RMSE convergence, zero/negative convergence frames,
 * history flush in inactive pipeline, idempotent freeze toggling, invalid Brigadier subcommands,
 * and maximum frame bounds.
 */
public class Tier2TemporalBoundaryTest {

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
   @DisplayName("F2.6.1: Single pixel discrepancy in 1,000,000 pixels yields exact RMSE = 0.001")
   void testConvergenceRmseSinglePixelSpike() {
      int size = 1_000_000;
      float[] frameA = new float[size];
      float[] frameB = new float[size];
      // Exactly 1 pixel differs by 1.0
      frameB[500_000] = 1.0f;

      double rmse = AlgorithmVerifiers.calculateExpectedRmse(frameA, frameB);
      // sqrt(1.0 / 1,000,000) = sqrt(1e-6) = 0.001
      assertEquals(0.001, rmse, 1e-9);
   }

   @Test
   @DisplayName("F2.6.2: Convergence calculation rejects zero or mismatched frame buffers")
   void testConvergenceProfilerZeroFramesRequested() {
      float[] empty = new float[0];
      assertThrows(IllegalArgumentException.class, () -> {
         AlgorithmVerifiers.calculateExpectedRmse(empty, empty);
      });

      float[] f10 = new float[10];
      float[] f20 = new float[20];
      assertThrows(IllegalArgumentException.class, () -> {
         AlgorithmVerifiers.calculateExpectedRmse(f10, f20);
      });
   }

   @Test
   @DisplayName("F2.6.3: History flush command executes without crash when pipeline is inactive")
   void testHistoryFlushWithoutPipelineActive() throws Exception {
      // With mock command source and inactive pipeline, execute flush
      int result = CommandTestHarness.execute(dispatcher, source, "/viewfinder history flush");
      assertEquals(1, result);
      assertEquals(1, context.getHistoryFlushedCount());
   }

   @Test
   @DisplayName("F2.6.4: Repeated freeze commands are idempotent and maintain target state")
   void testFreezeToggleIdempotentCalls() throws Exception {
      assertFalse(context.isCameraFrozen());

      // Set true 3 times
      CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera true");
      CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera true");
      CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera true");
      assertTrue(context.isCameraFrozen());

      // Set false twice
      CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera false");
      CommandTestHarness.execute(dispatcher, source, "/viewfinder freeze camera false");
      assertFalse(context.isCameraFrozen());
   }

   @Test
   @DisplayName("F2.6.5: Brigadier parser rejects invalid subcommands with reader parse error")
   void testBrigadierInvalidSubcommandHandling() {
      ParseResults<FabricClientCommandSource> parse = CommandTestHarness.parse(dispatcher, source, "/viewfinder invalid_subcommand_123");
      // The parse reader has remaining unparsed tokens or parse exceptions
      assertTrue(parse.getReader().canRead() || !parse.getExceptions().isEmpty(),
         "Parser must report unread tokens or exceptions for invalid command");
   }

   @Test
   @DisplayName("F2.6.6: Convergence command enforces maximum allowable frame upper bound (600)")
   void testConvergenceMaxFramesUpperLimit() {
      // 600 frames is valid
      ParseResults<FabricClientCommandSource> validParse = CommandTestHarness.parse(dispatcher, source, "/viewfinder convergence 600");
      assertFalse(validParse.getReader().canRead());
      assertTrue(validParse.getExceptions().isEmpty());

      // 10,000 frames exceeds Brigadier IntegerArgumentType bound (600)
      ParseResults<FabricClientCommandSource> invalidParse = CommandTestHarness.parse(dispatcher, source, "/viewfinder convergence 10000");
      assertTrue(invalidParse.getReader().canRead() || !invalidParse.getExceptions().isEmpty(),
         "Parser must reject frame counts exceeding 600");
   }
}
