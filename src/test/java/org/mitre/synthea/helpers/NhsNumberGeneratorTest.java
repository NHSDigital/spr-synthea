package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NhsNumberGeneratorTest {
  private static final String[] KEYS = {"generate.patient.nhs_number.range.start",
      "generate.patient.nhs_number.range.end", "generate.patient.nhs_number_prefix"};
  private final Map<String, String> originalConfig = new HashMap<>();

  /** Save the process-wide properties touched by these allocation checks. */
  @Before
  public void saveConfig() {
    for (String key : KEYS) {
      originalConfig.put(key, Config.get(key));
      Config.remove(key);
    }
  }

  /** Restore properties so other fixtures retain their original configuration. */
  @After
  public void restoreConfig() {
    originalConfig.forEach((key, value) -> {
      if (value == null) {
        Config.remove(key);
      } else {
        Config.set(key, value);
      }
    });
    NhsNumberGenerator.reset();
  }

  private static boolean weightedSumIsDivisibleBy11(String number) {
    int sum = 0;
    for (int digit = 0; digit < 10; digit++) {
      sum += Character.digit(number.charAt(digit), 10) * (10 - digit);
    }
    return sum % 11 == 0;
  }

  @Test
  public void randomNumbersHaveValidChecksumsAndAreUnique() {
    Config.set("generate.patient.nhs_number_prefix", "9");
    NhsNumberGenerator generator = new NhsNumberGenerator();
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(4242);
    Set<String> numbers = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String number = generator.randomNhsNumber(random);
      assertTrue(number.matches("9[0-9]{9}"));
      assertTrue(weightedSumIsDivisibleBy11(number));
      assertTrue(NhsNumberGenerator.isValid(number));
      assertTrue("Duplicate NHS number: " + number, numbers.add(number));
    }
  }

  @Test
  public void prefixIsConfigurable() {
    Config.set("generate.patient.nhs_number_prefix", "4");
    NhsNumberGenerator generator = new NhsNumberGenerator();
    String number = generator.randomNhsNumber(new DefaultRandomNumberGenerator(1));
    assertTrue(number.startsWith("4"));
    assertTrue(NhsNumberGenerator.isValid(number));
  }

  @Test
  public void invalidPrefixIsRejected() {
    Config.set("generate.patient.nhs_number_prefix", "10");
    assertThrows(IllegalArgumentException.class, NhsNumberGenerator::new);
  }

  @Test
  public void sameSeedGivesSameNumbers() {
    String a = new NhsNumberGenerator().randomNhsNumber(new DefaultRandomNumberGenerator(7));
    String b = new NhsNumberGenerator().randomNhsNumber(new DefaultRandomNumberGenerator(7));
    assertEquals(a, b);
  }

  @Test
  public void configuredRangeSkipsInvalidRootsAndReportsExhaustion() {
    // Root 900000005 would need the forbidden check digit 10.
    Config.set("generate.patient.nhs_number.range.start", "900000004");
    Config.set("generate.patient.nhs_number.range.end", "900000006");
    NhsNumberGenerator generator = new NhsNumberGenerator();
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(1);
    assertEquals("9000000041", generator.randomNhsNumber(random));
    assertEquals("9000000068", generator.randomNhsNumber(random));
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> generator.randomNhsNumber(random));
    assertEquals("Configured NHS number range exhausted", e.getMessage());
  }

  @Test
  public void incompleteOrInvalidRangesAreRejected() {
    String[][] ranges = {{"900000000", null}, {null, "900000000"}, {"900000010", "900000000"},
        {"-1", "5"}, {"abc", "900000000"}, {" ", ""}};
    for (String[] range : ranges) {
      Config.remove("generate.patient.nhs_number.range.start");
      Config.remove("generate.patient.nhs_number.range.end");
      if (range[0] != null) {
        Config.set("generate.patient.nhs_number.range.start", range[0]);
      }
      if (range[1] != null) {
        Config.set("generate.patient.nhs_number.range.end", range[1]);
      }
      assertThrows(range[0] + "-" + range[1], IllegalArgumentException.class,
          NhsNumberGenerator::new);
    }
  }

  @Test
  public void singleRootRangeIsAccepted() {
    Config.set("generate.patient.nhs_number.range.start", "900000004");
    Config.set("generate.patient.nhs_number.range.end", "900000004");
    assertEquals("9000000041",
        new NhsNumberGenerator().randomNhsNumber(new DefaultRandomNumberGenerator(1)));
  }

  @Test
  public void rangeBeyondNineDigitsIsRejected() {
    Config.set("generate.patient.nhs_number.range.start", "0");
    Config.set("generate.patient.nhs_number.range.end", "1000000000");
    assertThrows(IllegalArgumentException.class, NhsNumberGenerator::new);
  }

  @Test
  public void concurrentAllocationIsUnique() throws Exception {
    NhsNumberGenerator generator = new NhsNumberGenerator();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<List<String>>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        // identical seeds force every thread to race for the same candidates
        tasks.add(() -> {
          DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(99);
          List<String> out = new ArrayList<>();
          for (int i = 0; i < 250; i++) {
            out.add(generator.randomNhsNumber(random));
          }
          return out;
        });
      }
      Set<String> all = new HashSet<>();
      int total = 0;
      for (Future<List<String>> f : pool.invokeAll(tasks)) {
        for (String n : f.get()) {
          total++;
          all.add(n);
          assertTrue(NhsNumberGenerator.isValid(n));
        }
      }
      assertEquals(total, all.size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void isValidChecksKnownNumbers() {
    assertTrue(NhsNumberGenerator.isValid("9434765919"));
    assertTrue(NhsNumberGenerator.isValid("4010232137"));
    assertFalse(NhsNumberGenerator.isValid("9434765918"));
    assertFalse(NhsNumberGenerator.isValid("943476591"));
    assertFalse(NhsNumberGenerator.isValid("943476591X"));
    assertFalse(NhsNumberGenerator.isValid(null));
    // root 900000005 needs check digit 10, so no ten digit number with that root is valid
    for (int d = 0; d <= 9; d++) {
      assertFalse(NhsNumberGenerator.isValid("900000005" + d));
    }
  }

  @Test
  public void sharedInstanceIsResettable() {
    NhsNumberGenerator first = NhsNumberGenerator.getInstance();
    assertTrue(first == NhsNumberGenerator.getInstance());
    NhsNumberGenerator.reset();
    assertFalse(first == NhsNumberGenerator.getInstance());
  }
}
