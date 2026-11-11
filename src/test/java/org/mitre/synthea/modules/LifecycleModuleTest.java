package org.mitre.synthea.modules;

import static org.junit.Assert.assertEquals;

import java.util.regex.Pattern;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.NationalIdGenerator;
import org.mitre.synthea.helpers.NhsNumberGenerator;
import org.mitre.synthea.helpers.PhysiologyValueGenerator;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Nation;

public class LifecycleModuleTest {
  public static boolean deathByNaturalCauses;

  @BeforeClass
  public static void before() {
    deathByNaturalCauses = LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES;
  }

  @AfterClass
  public static void after() {
    LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES = deathByNaturalCauses;
  }

  /** Birth with a new generator of the person's national identifiers. */
  private static void birth(Person person, long time) {
    LifecycleModule.birth(person, time, person.getNation().newNationalIdGenerator());
  }

  private static Person newbornWithDemographics(long seed, String gender) {
    Person person = new Person(seed);
    person.attributes.put(Person.GENDER, gender);
    person.attributes.put(Person.FIRST_LANGUAGE, "english");
    person.attributes.put(Person.RACE, "white");
    person.attributes.put(Person.ETHNICITY, "nonhispanic");
    return person;
  }

  /** Run with the given country code, restoring the configured one afterwards. */
  private static void inNation(String countryCode, Runnable test) {
    String original = Config.get("generate.geography.country_code");
    try {
      Config.set("generate.geography.country_code", countryCode);
      test.run();
    } finally {
      Config.set("generate.geography.country_code", original);
    }
  }

  @Test
  public void testBirthAssignsValidNhsNumber() {
    inNation("GB-ENG", LifecycleModuleTest::assertBirthAssignsValidNhsNumber);
  }

  private static void assertBirthAssignsValidNhsNumber() {
    Person person = newbornWithDemographics(12345L, "F");
    birth(person, 0L);
    String nhsNumber = (String) person.attributes.get(Person.IDENTIFIER_NHS_NUMBER);
    Assert.assertTrue("Invalid NHS number " + nhsNumber, NhsNumberGenerator.isValid(nhsNumber));
    Assert.assertTrue(nhsNumber.startsWith("9"));
    // the US-style SSN is still populated for exporters that need it
    Assert.assertNotNull(person.attributes.get(Person.IDENTIFIER_SSN));
  }

  @Test
  public void testUpstreamNationHasNoNhsNumber() {
    inNation("US", () -> {
      Person person = newbornWithDemographics(12345L, "F");
      birth(person, 0L);
      Assert.assertNull(person.attributes.get(Person.IDENTIFIER_NHS_NUMBER));
      Assert.assertNotNull(person.attributes.get(Person.IDENTIFIER_SSN));
      assertEquals("US", person.attributes.get(Person.COUNTRY));
      assertEquals("US", person.attributes.get(Person.NATION));
    });
  }

  @Test
  public void testBirthAssignsSimulationCountryAndNation() {
    inNation("GB-ENG", () -> {
      Person person = newbornWithDemographics(12345L, "F");
      birth(person, 0L);
      assertEquals("GB", person.attributes.get(Person.COUNTRY));
      assertEquals("GB-ENG", person.attributes.get(Person.NATION));
    });
  }

  @Test
  public void testBirthKeepsSuppliedNhsNumber() {
    Person person = newbornWithDemographics(12345L, "M");
    person.attributes.put(Person.IDENTIFIER_NHS_NUMBER, "9434765919");
    birth(person, 0L);
    assertEquals("9434765919", person.attributes.get(Person.IDENTIFIER_NHS_NUMBER));
  }

  @Test
  public void testRunGeneratorIssuesUniqueNhsNumbers() {
    inNation("GB-ENG", () -> {
      NationalIdGenerator nationalIds = Nation.ENGLAND.newNationalIdGenerator();
      Person a = newbornWithDemographics(12345L, "F");
      LifecycleModule.birth(a, 0L, nationalIds);
      // same seed, so the same random NHS number would be drawn: the run's generator skips it
      Person b = newbornWithDemographics(12345L, "F");
      LifecycleModule.birth(b, 0L, nationalIds);
      Assert.assertNotEquals(a.attributes.get(Person.IDENTIFIER_NHS_NUMBER),
          b.attributes.get(Person.IDENTIFIER_NHS_NUMBER));
      Assert.assertTrue(NhsNumberGenerator.isValid(
          (String) b.attributes.get(Person.IDENTIFIER_NHS_NUMBER)));
    });
  }

  @Test
  public void testSuppliedNhsNumberIsReservedForTheRun() {
    inNation("GB-ENG", () -> {
      Person first = newbornWithDemographics(12345L, "F");
      birth(first, 0L);
      String issued = (String) first.attributes.get(Person.IDENTIFIER_NHS_NUMBER);

      NationalIdGenerator nationalIds = Nation.ENGLAND.newNationalIdGenerator();
      Person supplied = newbornWithDemographics(1L, "M");
      supplied.attributes.put(Person.IDENTIFIER_NHS_NUMBER, issued);
      LifecycleModule.birth(supplied, 0L, nationalIds);
      Person later = newbornWithDemographics(12345L, "F");
      LifecycleModule.birth(later, 0L, nationalIds);
      Assert.assertNotEquals(issued, later.attributes.get(Person.IDENTIFIER_NHS_NUMBER));
    });
  }

  @Test
  public void testNhsNumberDoesNotPerturbRandomStream() {
    inNation("GB-ENG", LifecycleModuleTest::assertNhsNumberDoesNotPerturbRandomStream);
  }

  private static void assertNhsNumberDoesNotPerturbRandomStream() {
    Person a = newbornWithDemographics(777L, "F");
    a.attributes.put(Person.IDENTIFIER_NHS_NUMBER, "9434765919");
    birth(a, 0L);
    Person b = newbornWithDemographics(777L, "F");
    birth(b, 0L);
    assertEquals(a.getCount(), b.getCount());
    assertEquals(a.attributes.get(Person.NAME), b.attributes.get(Person.NAME));
  }

  @Test
  public void testDeathByNaturalCauses() {
    LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES = true;
    Person person = new Person(0L);
    long time = System.currentTimeMillis();
    long birth = time - Utilities.convertTime("years", 100);
    person.attributes.put(Person.BIRTHDATE, birth);
    for (int i = 0; i < 100000; i++) {
      LifecycleModule.death(person, time);
    }
    assertEquals(false, person.alive(time));
  }

  @Test
  public void testLikelihoodOfDeathInputs() {
    // should handle zero to very old
    for (int age = 0; age < 100; age++) {
      double likelihood = LifecycleModule.likelihoodOfDeath(age);
      Assert.assertTrue(likelihood >= 0);
    }
  }

  @Test
  public void testAdherenceFade() {
    Person person = new Person(0L);
    long time = System.currentTimeMillis();
    person.attributes.put(Person.ADHERENCE, true);
    person.attributes.put(LifecycleModule.ADHERENCE_PROBABILITY, 1.0);
    for (int i = 0; i < 5; i++) {
      double before = (Double) person.attributes.get(LifecycleModule.ADHERENCE_PROBABILITY);
      LifecycleModule.adherence(person, time);
      double after = (Double) person.attributes.get(LifecycleModule.ADHERENCE_PROBABILITY);
      Assert.assertTrue(after < before);
    }
  }

  @Test
  public void testPercentileForBMI() {
    double percentile = LifecycleModule.percentileForBMI(18.37736191, "M", 26);
    Assert.assertEquals(0.9, percentile, 0.01);
  }

  @Test
  public void lookupGrowthChart() {
    // Uncomment to check performance.
    // long start = System.currentTimeMillis();
    // for (int i = 0; i < 1000000; i++) {
    double height = LifecycleModule.lookupGrowthChart("height", "M", 24, 0.5);
    Assert.assertEquals(86.86160934, height, 0.01);
    // }
    // long end = System.currentTimeMillis();
    // System.out.println("Time to complete: " + (end - start));
  }

  @Test
  public void lookupHeadCircumference() {
    double head = LifecycleModule.lookupGrowthChart("head", "F", 36, 0.9);
    Assert.assertEquals(50.57, head, 0.01);
  }

  @Test
  public void testPhysiologyEnabled() {
    boolean enablePhysiology = LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS;
    LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS = true;
    Person person = new Person(0L);

    // Need to set some attributes for birth to work properly
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.RACE, "white");
    person.attributes.put(Person.ETHNICITY, "english");

    birth(person, 0);

    // Person should have some PhysiologyValueGenerators
    Assert.assertEquals(person.vitalSigns.get(VitalSign.SYSTOLIC_BLOOD_PRESSURE).getClass(),
        PhysiologyValueGenerator.class);
    Assert.assertEquals(person.vitalSigns.get(VitalSign.DIASTOLIC_BLOOD_PRESSURE).getClass(),
        PhysiologyValueGenerator.class);

    LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS = enablePhysiology;
  }

  @Test
  public void testPassportFormatIsAlwaysEightDigits() throws Exception {
    Pattern eightDigitPassport = Pattern.compile("^X\\d{8}X$");
    int sampleSize = 2000;
    int issued = 0;

    java.lang.reflect.Method ageMethod = LifecycleModule.class.getDeclaredMethod(
        "age", Person.class, long.class);
    ageMethod.setAccessible(true);

    for (int i = 0; i < sampleSize; i++) {
      Person person = new Person(i);
      person.attributes.put(Person.GENDER, "F");
      person.attributes.put(Person.RACE, "white");
      person.attributes.put(Person.ETHNICITY, "english");
      long birth = 0L;
      birth(person, birth);

      long candidateTime = birth + Utilities.convertTime("years", 20);
      while (person.ageInYears(candidateTime) < 20) {
        candidateTime += Utilities.convertTime("days", 1);
      }

      ageMethod.invoke(null, person, candidateTime);

      String passport = (String) person.attributes.get(Person.IDENTIFIER_PASSPORT);
      if (passport != null) {
        issued++;
        Assert.assertTrue("Passport not 8 digits: " + passport,
            eightDigitPassport.matcher(passport).matches());
      }
    }
    Assert.assertTrue("Expected at least some passports to be issued", issued > 0);
  }
}
