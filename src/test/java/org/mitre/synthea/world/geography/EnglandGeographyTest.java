package org.mitre.synthea.world.geography;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mitre.synthea.world.geography.Nation.Resource.DEMOGRAPHICS;
import static org.mitre.synthea.world.geography.Nation.Resource.POSTCODES;

import com.google.common.collect.Table;

import java.awt.geom.Point2D;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;
import org.mitre.synthea.world.agents.Person;

/**
 * Checks the England (South West) geography data and that Location/Demographics load it.
 */
public class EnglandGeographyTest {
  private static final String REGION = "South West";
  /** Postcode areas in the South West region. */
  private static final Set<String> SOUTH_WEST_AREAS = new HashSet<>(Arrays.asList(
      "BA", "BH", "BS", "DT", "EX", "GL", "PL", "SN", "SP", "TA", "TQ", "TR"));
  /** UK outward code (postcode district), e.g. DT1, TA20, BS35. */
  private static final Pattern OUTWARD_CODE = Pattern.compile("^[A-Z]{1,2}[0-9][A-Z0-9]?$");

  private static Location location;
  private static Table<String, String, Demographics> demographics;

  /** Switch to the England configuration, as in synthea.properties. */
  @BeforeClass
  public static void useEngland() throws Exception {
    EnglandTestConfig.apply();
    assertEquals("geography/england/demographics.csv",
        Nation.ENGLAND.getResource(DEMOGRAPHICS));
    assertEquals("geography/england/postcodes.csv", Nation.ENGLAND.getResource(POSTCODES));
    location = new Location(Nation.ENGLAND, REGION, null);
    demographics = Demographics.load(Nation.ENGLAND, REGION);
  }

  /** Restore the original geography configuration. */
  @AfterClass
  public static void restoreConfig() {
    EnglandTestConfig.restore();
  }

  @Test
  public void regionLoads() {
    assertFalse(demographics.row(REGION).isEmpty());
    assertEquals("SW", Location.getAbbreviation(Nation.ENGLAND, REGION));
    assertEquals(REGION, Location.getStateName(Nation.ENGLAND, "SW"));
    assertEquals("Greenwich Mean Time", Location.getTimezoneByState(Nation.ENGLAND, REGION));
  }

  @Test
  public void distributionsSumToOne() {
    for (Demographics d : demographics.row(REGION).values()) {
      assertNotNull(d.city + " has the England ethnicity model", d.nationalEthnicity);
      assertEquals(d.city + " ages", 1.0, sum(d.ages), 0.005);
      assertEquals(d.city + " gender", 1.0, sum(d.gender), 0.005);
      assertEquals(d.city + " race", 1.0, sum(d.race), 0.005);
      // UK census groups, no US-only categories
      assertEquals(new HashSet<>(Arrays.asList("white", "mixed", "black", "asian", "other")),
          d.race.keySet());
      assertEquals(0.0, d.ethnicity, 0.0);
    }
  }

  @Test
  public void childrenAreRepresented() {
    for (Demographics d : demographics.row(REGION).values()) {
      for (String band : Arrays.asList("0..4", "5..9", "10..14", "15..19")) {
        assertTrue(d.city + " has no " + band + " population", d.ages.get(band) > 0.0);
      }
    }
  }

  @Test
  public void everyTownHasASouthWestPostcode() {
    for (Demographics d : demographics.row(REGION).values()) {
      List<String> postcodes = location.getZipCodes(d.city);
      assertFalse(d.city + " has no postcode", postcodes.isEmpty());
      for (String postcode : postcodes) {
        assertTrue(d.city + ": " + postcode, OUTWARD_CODE.matcher(postcode).matches());
        String area = postcode.replaceAll("[0-9].*$", "");
        assertTrue(d.city + " is outside the South West: " + postcode,
            SOUTH_WEST_AREAS.contains(area));
      }
    }
  }

  @Test
  public void pickedDemographicsUseCensusGroups() {
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(42L);
    Set<String> races = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      Demographics city = location.randomCity(random);
      races.add(city.pickRace(random));
      assertEquals("nonhispanic", city.pickEthnicity(random));
    }
    assertTrue(races.contains("white"));
    assertTrue(races.contains("asian"));
    assertTrue(races.contains("mixed"));
    assertFalse(races.contains("hispanic"));
    assertFalse(races.contains("native"));
    assertFalse(races.contains("hawaiian"));
  }

  @Test
  public void assignedPointsAreInSouthWestEngland() {
    for (long seed = 0; seed < 200; seed++) {
      Person person = new Person(seed);
      String city = location.randomCityName(person);
      location.assignPoint(person, city);
      Point2D.Double coord = (Point2D.Double) person.attributes.get(Person.COORDINATE);
      assertNotNull(coord);
      // longitude (x) and latitude (y) of the South West, allowing for the random jitter
      assertTrue(city + " lon " + coord.x, coord.x > -6.5 && coord.x < -1.4);
      assertTrue(city + " lat " + coord.y, coord.y > 49.8 && coord.y < 52.2);
    }
  }

  @Test
  public void birthPlaceUsesConfiguredCountry() {
    String[] birthPlace = location.randomBirthPlace(new DefaultRandomNumberGenerator(1L));
    assertEquals(REGION, birthPlace[1]);
    assertEquals("GB", birthPlace[2]);
  }

  @Test
  public void socialDeterminantsLoadForEnglishCounties() {
    Person person = new Person(7L);
    person.attributes.put(Person.COUNTY, "Dorset");
    location.setSocialDeterminants(person);
    assertNotNull(person.attributes.get(Person.FOOD_INSECURITY));
    assertNotNull(person.attributes.get(Person.EMPLOYMENT_MODEL));
  }

  private static double sum(Map<String, Double> map) {
    return map.values().stream().mapToDouble(Double::doubleValue).sum();
  }
}
