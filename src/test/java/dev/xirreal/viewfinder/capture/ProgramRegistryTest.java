package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProgramRegistryTest {
   @AfterEach
   void clearRegistry() {
      ProgramRegistry.clear();
   }

   @Test
   void resolvesAndRemovesProgramsByExactName() {
      ProgramRegistry.register(17, "gbuffers_terrain");
      ProgramRegistry.register(23, "composite");

      assertEquals(17, ProgramRegistry.resolve("gbuffers_terrain"));
      assertEquals(2, ProgramRegistry.listPrograms().get("count").getAsInt());

      ProgramRegistry.remove(17);
      assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve("gbuffers_terrain"));
   }

   @Test
   void rejectsAmbiguousNamesInsteadOfChoosingTheWrongProgram() {
      ProgramRegistry.register(17, "composite");
      ProgramRegistry.register(23, "composite");

      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve("composite"));
      assertTrue(error.getMessage().contains("ids 17 and 23"));
   }

   @Test
   void tracksComputeProgramsCorrectly() {
      ProgramRegistry.register(42, "setup");
      ProgramRegistry.registerCompute(42);

      assertTrue(ProgramRegistry.isCompute(42));
      assertFalse(ProgramRegistry.isCompute(17));
      assertEquals("setup", ProgramRegistry.getName(42));

      ProgramRegistry.remove(42);
      assertFalse(ProgramRegistry.isCompute(42));
   }
}
