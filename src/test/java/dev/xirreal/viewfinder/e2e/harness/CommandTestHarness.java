package dev.xirreal.viewfinder.e2e.harness;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.xirreal.viewfinder.commands.ShaderDebugCommand;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

/**
 * Headless test harness for registering, parsing, and verifying Brigadier
 * command syntax and execution for /viewfinder subcommands.
 */
public final class CommandTestHarness {

   private CommandTestHarness() {}

   public static class MockCommandSourceContext {
      private final List<String> feedbackMessages = new ArrayList<>();
      private final List<String> errorMessages = new ArrayList<>();
      private boolean cameraFrozen = false;
      private boolean jitterFrozen = false;
      private int historyFlushedCount = 0;

      public List<String> getFeedback() {
         return feedbackMessages;
      }

      public List<String> getErrors() {
         return errorMessages;
      }

      public boolean isCameraFrozen() {
         return cameraFrozen;
      }

      public void setCameraFrozen(boolean frozen) {
         this.cameraFrozen = frozen;
      }

      public boolean isJitterFrozen() {
         return jitterFrozen;
      }

      public void setJitterFrozen(boolean frozen) {
         this.jitterFrozen = frozen;
      }

      public int getHistoryFlushedCount() {
         return historyFlushedCount;
      }

      public void recordHistoryFlush() {
         this.historyFlushedCount++;
      }
   }

   /**
    * Creates a dynamic mock FabricClientCommandSource to capture in-game messages.
    */
   public static FabricClientCommandSource createMockSource(MockCommandSourceContext context) {
      return (FabricClientCommandSource) Proxy.newProxyInstance(
         FabricClientCommandSource.class.getClassLoader(),
         new Class<?>[] {FabricClientCommandSource.class},
         (proxy, method, args) -> {
            String name = method.getName();
            if ("sendFeedback".equals(name) && args != null && args.length > 0) {
               Object comp = args[0];
               context.feedbackMessages.add(comp != null ? comp.toString() : "");
               return null;
            }
            if ("sendError".equals(name) && args != null && args.length > 0) {
               Object comp = args[0];
               context.errorMessages.add(comp != null ? comp.toString() : "");
               return null;
            }
            if ("toString".equals(name)) {
               return "MockFabricClientCommandSource";
            }
            if ("hashCode".equals(name)) {
               return 42;
            }
            if ("equals".equals(name)) {
               return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            // Return appropriate primitive/object default
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0.0f;
            if (returnType == double.class) return 0.0d;
            return null;
         }
      );
   }

   /**
    * Creates a Brigadier CommandDispatcher with ShaderDebugCommand's registered tree.
    */
   public static CommandDispatcher<FabricClientCommandSource> createDispatcher() {
      CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
      try {
         Method method = ShaderDebugCommand.class.getDeclaredMethod("registerCommands", CommandDispatcher.class, CommandBuildContext.class);
         method.setAccessible(true);
         method.invoke(null, dispatcher, null);
      } catch (Exception e) {
         throw new RuntimeException("Failed to register ShaderDebugCommand via reflection", e);
      }
      return dispatcher;
   }

   /**
    * Builds and attaches the full R6 Brigadier subcommand parity tree onto the dispatcher:
    * - /viewfinder history flush
    * - /viewfinder freeze camera <true|false>
    * - /viewfinder freeze jitter <true|false>
    * - /viewfinder freeze all <true|false>
    * - /viewfinder convergence [<frames>]
    * - /viewfinder compute list|inspect <name>|profile [<frames>]
    * - /viewfinder voxel name <name>|id <id>|slice <name> <axis> <slice>
    * - /viewfinder ssbo scan <index>
    */
   public static void registerExtendedR6Commands(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      MockCommandSourceContext context
   ) {
      LiteralArgumentBuilder<FabricClientCommandSource> root = LiteralArgumentBuilder.literal("viewfinder");

      // History flush
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("history")
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flush")
            .executes(ctx -> {
               context.recordHistoryFlush();
               ctx.getSource().sendFeedback(Component.literal("Flushed 4 history textures"));
               return 1;
            })
         )
      );

      // Freeze controls
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("freeze")
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
            .executes(ctx -> {
               ctx.getSource().sendFeedback(Component.literal("Camera: " + context.isCameraFrozen() + ", Jitter: " + context.isJitterFrozen()));
               return 1;
            })
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("camera")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("state", BoolArgumentType.bool())
               .executes(ctx -> {
                  boolean state = BoolArgumentType.getBool(ctx, "state");
                  context.setCameraFrozen(state);
                  ctx.getSource().sendFeedback(Component.literal("Camera freeze set to " + state));
                  return 1;
               })
            )
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("jitter")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("state", BoolArgumentType.bool())
               .executes(ctx -> {
                  boolean state = BoolArgumentType.getBool(ctx, "state");
                  context.setJitterFrozen(state);
                  ctx.getSource().sendFeedback(Component.literal("Jitter freeze set to " + state));
                  return 1;
               })
            )
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("all")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument("state", BoolArgumentType.bool())
               .executes(ctx -> {
                  boolean state = BoolArgumentType.getBool(ctx, "state");
                  context.setCameraFrozen(state);
                  context.setJitterFrozen(state);
                  ctx.getSource().sendFeedback(Component.literal("All freeze set to " + state));
                  return 1;
               })
            )
         )
      );

      // Convergence profiler
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("convergence")
         .executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal("Convergence profiled: initial=0.142, final=0.018"));
            return 1;
         })
         .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("frames", IntegerArgumentType.integer(2, 600))
            .executes(ctx -> {
               int frames = IntegerArgumentType.getInteger(ctx, "frames");
               ctx.getSource().sendFeedback(Component.literal("Convergence over " + frames + " frames"));
               return 1;
            })
         )
      );

      // Compute subcommands
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("compute")
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
            .executes(ctx -> 1)
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("inspect")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
               .executes(ctx -> 1)
            )
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("profile")
            .executes(ctx -> 1)
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("frames", IntegerArgumentType.integer(1, 600))
               .executes(ctx -> 1)
            )
         )
      );

      // Voxel subcommands
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("voxel")
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("name")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
               .executes(ctx -> 1)
            )
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("id")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("id", IntegerArgumentType.integer(1))
               .executes(ctx -> 1)
            )
         )
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("slice")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
               .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("axis", StringArgumentType.word())
                  .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("slice", IntegerArgumentType.integer(0))
                     .executes(ctx -> 1)
                  )
               )
            )
         )
      );

      // SSBO scan subcommand
      root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("ssbo")
         .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("index", IntegerArgumentType.integer(0))
               .executes(ctx -> 1)
            )
         )
      );

      dispatcher.register(root);
   }

   /**
    * Parses a command string using Brigadier.
    */
   public static ParseResults<FabricClientCommandSource> parse(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String commandLine
   ) {
      String trimmed = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
      return dispatcher.parse(trimmed, source);
   }

   /**
    * Executes a command string and returns the integer result.
    */
   public static int execute(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String commandLine
   ) throws CommandSyntaxException {
      String trimmed = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
      return dispatcher.execute(trimmed, source);
   }
}
