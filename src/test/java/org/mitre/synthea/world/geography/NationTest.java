package org.mitre.synthea.world.geography;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;

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
  public void englandSettings() {
    assertEquals(Nation.ENGLAND, Nation.current());
    assertEquals("GB-ENG", Nation.configuredCode());
    assertEquals("GB", Nation.isoCountryCode());
    assertEquals("South West", Nation.defaultState());
    assertEquals("http://hl7.org/fhir/sid/passport-GBR", Nation.passportUri());
    assertEquals("geography/england", Nation.ENGLAND.resourceFolder());
    assertTrue(Nation.current().usesNhsNumbers());
  }

  @Test
  public void resolvesCountryCodes() {
    assertSame(Nation.ENGLAND, Nation.fromCountryCode("GB-ENG"));
    assertSame(Nation.ENGLAND, Nation.fromCountryCode(" gb-eng "));
    assertSame(Nation.UPSTREAM, Nation.fromCountryCode("US"));
    assertSame(Nation.UPSTREAM, Nation.fromCountryCode("CA"));
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
  public void upstreamKeepsUsBehaviour() {
    Config.set(Nation.COUNTRY_CODE_KEY, "US");
    assertEquals(Nation.UPSTREAM, Nation.current());
    assertEquals("US", Nation.isoCountryCode());
    assertEquals("Massachusetts", Nation.defaultState());
    assertEquals("http://hl7.org/fhir/sid/passport-USA", Nation.passportUri());
    assertFalse(Nation.current().usesNhsNumbers());
    assertEquals("geography/demographics.csv", Nation.Resource.DEMOGRAPHICS.path());
    assertEquals("geography/zipcodes.csv", Nation.Resource.POSTCODES.path());
    assertEquals("names.yml", Nation.Resource.NAMES.path());
    assertEquals("providers/nursing.csv", Nation.Resource.NURSING.path());
    assertNull(Nation.Resource.ETHNICITY.path());
    assertNull(NationalEthnicity.current());
  }

  @Test
  public void missingNationFilesAreNotUsed() {
    // no England file: the resource is not used
    assertNull(Nation.Resource.NURSING.path());
    assertNull(Nation.Resource.VETERANS.path());
  }

  @Test
  public void configuredFileOverridesNationFolder() {
    Config.set(Nation.Resource.NAMES.configKey, "names.yml");
    assertEquals("names.yml", Nation.Resource.NAMES.path());
    Config.set(Nation.Resource.HOSPICE.configKey, " ");
    assertNull("blank disables the resource", Nation.Resource.HOSPICE.path());
  }

  @Test
  public void englandEthnicityModelLoads() {
    NationalEthnicity england = NationalEthnicity.current();
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
  public void resolutionIsCachedUntilConfigChanges() {
    Config.set(Nation.COUNTRY_CODE_KEY, "US");
    String names = Nation.Resource.NAMES.path();
    assertSame("cached between config changes", names, Nation.Resource.NAMES.path());
    long version = Config.version();

    Config.set(Nation.Resource.NAMES.configKey, "custom.yml");
    assertTrue(Config.version() > version);
    assertEquals("custom.yml", Nation.Resource.NAMES.path());
    Config.remove(Nation.Resource.NAMES.configKey);
    assertEquals("names.yml", Nation.Resource.NAMES.path());
    Config.set(Nation.COUNTRY_CODE_KEY, "GB-ENG");
    assertEquals(Nation.ENGLAND, Nation.current());
  }
}
