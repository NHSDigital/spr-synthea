package org.mitre.synthea.world.concepts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;
import org.mitre.synthea.helpers.SimpleYML;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.geography.Demographics;
import org.mitre.synthea.world.geography.EnglandTestConfig;
import org.mitre.synthea.world.geography.Nation;
import org.mitre.synthea.world.geography.NationalEthnicity;

/**
 * Checks the England name lists and the UK ethnic group and main language handling.
 */
public class EnglandNamesAndLanguagesTest {
  private static final List<String> UK_GROUPS =
      Arrays.asList("white", "mixed", "asian", "black", "other");
  private static SimpleYML ukNames;
  private static NationalEthnicity england;

  /** Switch to England, so Names loads geography/england/names.yml. */
  @BeforeClass
  public static void useEngland() throws Exception {
    EnglandTestConfig.apply();
    assertEquals("geography/england/names.yml", Nation.Resource.NAMES.path());
    Names.reloadNames();
    ukNames = new SimpleYML(Utilities.readResource("geography/england/names.yml"));
    england = NationalEthnicity.current();
    assertNotNull(england);
  }

  /** Restore the original configuration and names. */
  @AfterClass
  public static void restoreNames() {
    EnglandTestConfig.restore();
    Names.reloadNames();
  }

  @SuppressWarnings("unchecked")
  private static Set<String> list(String key) {
    return new HashSet<>((List<String>) ukNames.get(key));
  }

  @Test
  public void englishNamesComeFromUkLists() {
    Set<String> male = list("english.M");
    Set<String> female = list("english.F");
    Set<String> family = list("english.family");
    assertTrue(family.contains("Smith"));
    assertTrue(family.contains("Patel"));
    for (int i = 0; i < 200; i++) {
      Person person = new Person(i);
      String first = Names.fakeFirstName("M", "english", person);
      String firstF = Names.fakeFirstName("F", "english", person);
      String last = Names.fakeLastName("english", person);
      if (Names.appendNumbersToNames) {
        first = first.replaceAll("[0-9]+$", "");
        firstF = firstF.replaceAll("[0-9]+$", "");
        last = last.replaceAll("[0-9]+$", "");
      }
      assertTrue(first, male.contains(first));
      assertTrue(firstF, female.contains(firstF));
      assertTrue(last, family.contains(last));
    }
  }

  @Test
  public void spanishNamesStillAvailable() {
    Set<String> spanish = list("spanish.family");
    assertFalse(spanish.isEmpty());
    String last = Names.fakeLastName("spanish", new Person(1L));
    if (Names.appendNumbersToNames) {
      last = last.replaceAll("[0-9]+$", "");
    }
    assertTrue(last, spanish.contains(last));
  }

  @Test
  public void addressesUseUkStreetTypes() {
    Set<String> types = list("street.type");
    assertTrue(types.contains("Close"));
    assertFalse(types.contains("Boulevard"));
    for (int i = 0; i < 50; i++) {
      String address = Names.fakeAddress(false, new Person(i));
      String type = address.substring(address.lastIndexOf(' ') + 1);
      assertTrue(address, types.contains(type));
    }
    String withFlat = Names.fakeAddress(true, new Person(7L));
    assertTrue(withFlat, withFlat.matches(".* (Flat|Apartment|Unit) [0-9]+$"));
  }

  @Test
  public void englandLanguagesMostlyEnglishAndAllExportable() throws Exception {
    Map<?, ?> lookup = new Gson().fromJson(
        Utilities.readResource("language_lookup.json"), Map.class);
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(42L);
    Map<String, Integer> asianCounts = new HashMap<>();
    for (String group : UK_GROUPS) {
      int english = 0;
      int n = 5000;
      for (int i = 0; i < n; i++) {
        String language = england.pickMainLanguage(group, random);
        assertNotNull(group + " language " + language + " missing from language_lookup.json",
            lookup.get(language));
        if ("english".equals(language)) {
          english++;
        }
        if ("asian".equals(group)) {
          asianCounts.merge(language, 1, Integer::sum);
        }
      }
      double share = (double) english / n;
      assertTrue(group + " english share " + share, share > 0.75 && share < 0.99);
    }
    assertTrue(asianCounts.containsKey("urdu"));
    assertTrue(asianCounts.containsKey("panjabi"));
    assertFalse(asianCounts.containsKey("japanese"));
    assertEquals("english",
        england.pickMainLanguage("unknown", random));
  }

  @Test
  public void englandDemographicsUseEnglandLanguagesAndNeverSpanishForHispanic() {
    Demographics uk = new Demographics();
    uk.nationalEthnicity = england;
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(3L);
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 5000; i++) {
      seen.add(uk.languageFromRaceAndEthnicity("white", "nonhispanic", random));
    }
    assertTrue(seen.contains("polish"));
    assertTrue(seen.contains("romanian"));

    Demographics us = new Demographics();
    seen.clear();
    for (int i = 0; i < 5000; i++) {
      seen.add(us.languageFromRaceAndEthnicity("white", "nonhispanic", random));
    }
    assertFalse("US distribution unchanged", seen.contains("romanian"));
  }

  @Test
  public void mixedEthnicGroupHasCodes() throws Exception {
    Map<?, ?> codes = new Gson().fromJson(
        Utilities.readResource("race_ethnicity_codes.json"), Map.class);
    assertEquals("2131-1", codes.get("mixed"));
    assertEquals("2131-1", RaceAndEthnicity.LOOK_UP_CDC_RACE.get("mixed"));
    assertEquals("2186-5", RaceAndEthnicity.LOOK_UP_CDC_ETHNICITY_CODE.get("mixed"));
    assertEquals("non-hispanic", RaceAndEthnicity.LOOK_UP_CDC_ETHNICITY_DISPLAY.get("mixed"));
  }
}
