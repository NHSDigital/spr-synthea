package org.mitre.synthea.world.geography;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mitre.synthea.world.geography.Nation.Resource.DEMOGRAPHICS;
import static org.mitre.synthea.world.geography.Nation.Resource.ETHNICITY;
import static org.mitre.synthea.world.geography.Nation.Resource.HOSPICE;
import static org.mitre.synthea.world.geography.Nation.Resource.NAMES;
import static org.mitre.synthea.world.geography.Nation.Resource.NURSING;
import static org.mitre.synthea.world.geography.Nation.Resource.PAYER_PLANS;
import static org.mitre.synthea.world.geography.Nation.Resource.POSTCODES;
import static org.mitre.synthea.world.geography.Nation.Resource.VETERANS;
import static org.mitre.synthea.world.geography.Nation.Resource.VITAL_SIGN_SNOMED;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.export.FhirR4;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;
import org.mitre.synthea.helpers.NhsNumberGenerator;
import org.mitre.synthea.helpers.SocialSecurityNumberGenerator;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;

/**
 * Checks nation selection from generate.geography.country_code and per-nation resource lookup.
 */
public class NationTest {

  @Before
  public void useEngland() {
    EnglandTestConfig.apply();
  }

  @After
  public void restore() {
    EnglandTestConfig.restore();
  }

  @Test
  public void englandIsTheDefault() {
    Config.remove(Nation.COUNTRY_CODE_KEY);
    Nation england = Nation.configured();
    assertEquals(Nation.ENGLAND, england);
    assertEquals("GB-ENG", england.code());
    assertEquals("GB", england.isoCountryCode());
    assertEquals("South West", england.defaultState());
    assertEquals("http://hl7.org/fhir/sid/passport-GBR", england.passportUri());
    assertTrue(england.newNationalIdGenerator() instanceof NhsNumberGenerator);
  }

  @Test
  public void resolvesCountryCodes() {
    assertSame(Nation.ENGLAND, Nation.fromCountryCode("GB-ENG"));
    assertSame(Nation.ENGLAND, Nation.fromCountryCode(" gb-eng "));
    // people get the canonical code in Person.NATION, whatever the configured spelling
    assertEquals("GB-ENG", Nation.fromCountryCode(" gb-eng ").code());
    assertSame(Nation.USA, Nation.fromCountryCode("US"));
    assertSame(Nation.USA, Nation.fromCountryCode("CA"));
  }

  @Test
  public void otherUkNationsFailClearly() {
    assertFails("GB-WLS", "WALES");
    assertFails("GB-CYM", "WALES");
    assertFails("GB-SCT", "CHI");
    assertFails("GB-NIR", "H&C");
    assertFails("GB", "ambiguous");
    assertFails("UK", "ambiguous");
  }

  private static void assertFails(String code, String expectedMessage) {
    try {
      Nation.fromCountryCode(code);
      fail(code + " should not resolve to a nation");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }
  }

  @Test
  public void usaKeepsUsBehaviour() {
    Config.set(Nation.COUNTRY_CODE_KEY, "US");
    Nation usa = Nation.configured();
    assertEquals(Nation.USA, usa);
    assertEquals("US", usa.code());
    assertEquals("US", usa.isoCountryCode());
    assertEquals("Massachusetts", usa.defaultState());
    assertEquals("http://hl7.org/fhir/sid/passport-USA", usa.passportUri());
    assertTrue(usa.newNationalIdGenerator() instanceof SocialSecurityNumberGenerator);
  }

  @Test
  public void usaCodeIsTheConfiguredNonUkCode() {
    Config.set(Nation.COUNTRY_CODE_KEY, " ca ");
    assertEquals("CA", Nation.USA.code());
    assertEquals("CA", Nation.USA.isoCountryCode());
    // the configured nation is England, so the USA data is used for the US
    Config.set(Nation.COUNTRY_CODE_KEY, "GB-ENG");
    assertEquals("US", Nation.USA.code());
  }

  @Test
  public void eachNationResolvesItsOwnResourcesWhateverIsConfigured() {
    // England is configured, but USA resources are still available for the US nation
    assertEquals(Nation.ENGLAND, Nation.configured());
    assertEquals("geography/demographics.csv",
        Nation.USA.getResource(DEMOGRAPHICS));
    assertEquals("geography/zipcodes.csv", Nation.USA.getResource(POSTCODES));
    assertEquals("names.yml", Nation.USA.getResource(NAMES));
    assertEquals("providers/nursing.csv", Nation.USA.getResource(NURSING));
    assertNull(Nation.USA.getResource(ETHNICITY));
    assertNull(Nation.USA.getResource(VITAL_SIGN_SNOMED));
    assertNull(NationalEthnicity.of(Nation.USA));
    assertEquals("geography/england/names.yml", Nation.ENGLAND.getResource(NAMES));
    assertNotNull(NationalEthnicity.of(Nation.ENGLAND));
  }

  @Test
  public void englandResourcesComeFromItsFolder() throws Exception {
    assertEquals("geography/england", Nation.ENGLAND.resourceFolder());
    for (Nation.Resource resource : Nation.Resource.values()) {
      String path = Nation.ENGLAND.getResource(resource);
      if (path != null) {
        assertTrue(resource + ": " + path, path.startsWith("geography/england/"));
        assertNotNull(resource + " missing: " + path, Utilities.readResource(path));
      }
    }
    assertEquals("geography/england/postcodes.csv",
        Nation.ENGLAND.getResource(POSTCODES));
    assertEquals("geography/england/payers/insurance_plans.csv",
        Nation.ENGLAND.getResource(PAYER_PLANS));
    // no England file: the provider type is not used
    assertNull(Nation.ENGLAND.getResource(NURSING));
    assertNull(Nation.ENGLAND.getResource(VETERANS));
  }

  @Test
  public void configuredFileOverridesNationFolder() {
    Config.set(Nation.Resource.NAMES.configKey, "names.yml");
    assertEquals("names.yml", Nation.ENGLAND.getResource(NAMES));
    Config.set(Nation.Resource.HOSPICE.configKey, " ");
    assertNull("blank disables the resource", Nation.ENGLAND.getResource(HOSPICE));
  }

  @Test
  public void englandEthnicityModelLoads() {
    NationalEthnicity england = NationalEthnicity.of(Nation.ENGLAND);
    assertNotNull(england);
    assertSame(england, NationalEthnicity.load("geography/england/ethnicity.json"));
    assertEquals(5, england.getGroups().size());
    assertTrue(england.getCategorySystem().endsWith("UKCore-EthnicCategoryEngland"));
    assertEquals("White - British", england.getCategoryDisplay("A"));
    DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(1L);
    assertEquals("C", england.pickCategory("white", "polish", random));
    assertEquals("S", england.pickCategory("unknown", "english", random));
    String asian = england.pickCategory("asian", "english", random);
    assertNotNull(england.getCategoryDisplay(asian));
  }

  @Test
  public void englandVitalSignSnomedLoads() {
    assertEquals("271649006", TestAccess.vitalSign(Nation.ENGLAND, "8480-6"));
    assertNull(TestAccess.vitalSign(Nation.USA, "8480-6"));
  }

  /** Access to FhirR4's protected vital sign mappings. */
  private static class TestAccess extends FhirR4 {
    static String vitalSign(Nation nation, String loinc) {
      HealthRecord.Code code = vitalSignSnomed(nation).get(loinc);
      return code == null ? null : code.code;
    }
  }

  @Test
  public void resolutionIsCachedUntilConfigChanges() {
    String names = Nation.ENGLAND.getResource(NAMES);
    assertSame("cached between config changes", names,
        Nation.ENGLAND.getResource(NAMES));
    long version = Config.version();

    Config.set(Nation.Resource.NAMES.configKey, "custom.yml");
    assertTrue(Config.version() > version);
    assertEquals("custom.yml", Nation.ENGLAND.getResource(NAMES));
    Config.remove(Nation.Resource.NAMES.configKey);
    assertEquals("geography/england/names.yml", Nation.ENGLAND.getResource(NAMES));
  }

  @Test
  public void locationCarriesItsNation() {
    Location england = new Location(Nation.ENGLAND, "South West", null);
    assertEquals(Nation.ENGLAND, england.getNation());
    assertEquals("GB", england.randomBirthPlace(new DefaultRandomNumberGenerator(1L))[2]);
    // the two-argument constructor uses the configured nation
    assertEquals(Nation.ENGLAND, new Location("South West", null).getNation());

    // a US location can be built while England is configured, without changing Config
    Location us = new Location(Nation.USA, "Massachusetts", null);
    assertEquals(Nation.USA, us.getNation());
    assertEquals("MA", Location.getAbbreviation(Nation.USA, "Massachusetts"));
    assertEquals("Massachusetts", Location.getStateName(Nation.USA, "MA"));
  }

  @Test
  public void personNationComesFromTheirLocation() {
    Person person = new Person(1L);
    // no location (as in some unit tests): the configured nation
    assertEquals(Nation.ENGLAND, person.getNation());
    person.attributes.put(Person.LOCATION, new Location(Nation.USA, "Massachusetts", null));
    assertEquals(Nation.USA, person.getNation());
  }
}
