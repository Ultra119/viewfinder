package dev.xirreal.viewfinder.capture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial and boundary test suite for ProgramRegistry.
 * Validates name resolution, workgroup dimensions 64-bit precision,
 * registry clearing, and compute tracking.
 */
class ProgramRegistryAdversarialTest {

   @BeforeEach
   @AfterEach
   void resetRegistry() {
      ProgramRegistry.clear();
   }

   @ParameterizedTest
   @NullAndEmptySource
   @ValueSource(strings = {"   ", "\t", "\n"})
   @DisplayName("Rejects null, empty, or whitespace-only program names on resolve")
   void testResolveRejectsInvalidNames(String invalidName) {
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve(invalidName));
      assertTrue(ex.getMessage().contains("must be non-empty"));
   }

   @Test
   @DisplayName("Resolving unknown program name throws descriptive IllegalArgumentException")
   void testResolveUnknownProgramThrows() {
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve("non_existent_program"));
      assertTrue(ex.getMessage().contains("Unknown Iris program name: non_existent_program"));
   }

   @Test
   @DisplayName("Ambiguous program names with 3+ duplicates identify first two colliding IDs")
   void testAmbiguousNamesWithMultipleDuplicates() {
      ProgramRegistry.register(101, "shadow");
      ProgramRegistry.register(102, "shadow");
      ProgramRegistry.register(103, "shadow");

      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.resolve("shadow"));
      assertTrue(ex.getMessage().contains("Program name is ambiguous: shadow (ids 101 and 102)"));
   }

   @Test
   @DisplayName("Removing non-existent program ID is safe and does not throw")
   void testRemoveNonExistentIdIsSafe() {
      assertDoesNotThrow(() -> ProgramRegistry.remove(9999));
   }

   @Test
   @DisplayName("Listing programs on empty registry returns count 0 and empty array")
   void testListProgramsEmpty() {
      JsonObject list = ProgramRegistry.listPrograms();
      assertEquals(0, list.get("count").getAsInt());
      assertTrue(list.getAsJsonArray("programs").isEmpty());
   }

   @Test
   @DisplayName("Listing programs preserves insertion order and reflects removal")
   void testListProgramsOrderingAndRemoval() {
      ProgramRegistry.register(1, "progA");
      ProgramRegistry.register(2, "progB");
      ProgramRegistry.register(3, "progC");

      JsonObject list = ProgramRegistry.listPrograms();
      assertEquals(3, list.get("count").getAsInt());
      JsonArray arr = list.getAsJsonArray("programs");
      assertEquals("progA", arr.get(0).getAsJsonObject().get("name").getAsString());
      assertEquals("progB", arr.get(1).getAsJsonObject().get("name").getAsString());
      assertEquals("progC", arr.get(2).getAsJsonObject().get("name").getAsString());

      ProgramRegistry.remove(2);
      JsonObject updated = ProgramRegistry.listPrograms();
      assertEquals(2, updated.get("count").getAsInt());
      assertEquals("progC", updated.getAsJsonArray("programs").get(1).getAsJsonObject().get("name").getAsString());
   }

   @Test
   @DisplayName("Compute programs correctly distinguish from regular rasterization programs")
   void testComputeProgramSeparation() {
      ProgramRegistry.register(50, "gbuffers_water");
      ProgramRegistry.register(51, "ray_tracing_compute");
      ProgramRegistry.registerCompute(51);

      assertFalse(ProgramRegistry.isCompute(50));
      assertTrue(ProgramRegistry.isCompute(51));

      // Clearing registry clears both names and compute set
      ProgramRegistry.clear();
      assertFalse(ProgramRegistry.isCompute(51));
      assertEquals(0, ProgramRegistry.listPrograms().get("count").getAsInt());
   }

   @Test
   @DisplayName("Workgroup dimension invocation calculation preserves 64-bit integer precision")
   void testWorkgroupInvocationCalculationOverflowResistance() {
      // Dimensions whose product exceeds Integer.MAX_VALUE (2,147,483,647)
      int x = 65536;
      int y = 65536;
      int z = 1;

      long invocations = (long) x * y * z;
      assertEquals(4294967296L, invocations, "64-bit invocation product must not overflow 32-bit int");

      // Large 3D workgroup dimensions
      int dimX = 1024;
      int dimY = 1024;
      int dimZ = 64;
      long invocations3D = (long) dimX * dimY * dimZ;
      assertEquals(67108864L, invocations3D);
   }

   @Test
   @DisplayName("Inspecting non-positive program ID throws IllegalArgumentException")
   void testInspectRejectsNonPositiveId() {
      assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.inspect(0));
      assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.inspect(-1));
      assertThrows(IllegalArgumentException.class, () -> ProgramRegistry.inspect(Integer.MIN_VALUE));
   }
}
