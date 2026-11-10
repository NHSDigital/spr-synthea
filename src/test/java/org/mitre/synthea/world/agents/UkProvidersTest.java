package org.mitre.synthea.world.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.DefaultRandomNumberGenerator;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Provider.ProviderType;
import org.mitre.synthea.world.concepts.ClinicianSpecialty;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.healthinsurance.InsurancePlan;
import org.mitre.synthea.world.geography.Location;

/**
 * Checks the England (South West) provider and NHS payer data, loaded with the main (UK)
 * synthea.properties defaults rather than the US test overrides.
 */
public class UkProvidersTest {
  private static final String REGION = "South West";
  /** Full UK postcode, e.g. "EX2 5DW" or "BS1 3NU". */
  private static final Pattern POSTCODE =
      Pattern.compile("^[A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{2}$");

  private static final Map<String, String> savedConfig = new HashMap<>();
  private static Location location;
  private static final DefaultRandomNumberGenerator random = new DefaultRandomNumberGenerator(1L);

  /** Apply the main synthea.properties geography, names, provider and payer settings. */
  @BeforeClass
  public static void useUkDefaults() throws Exception {
    Properties main = new Properties();
    main.load(Config.class.getResourceAsStream("/synthea.properties"));
    for (String key : main.stringPropertyNames()) {
      if (key.startsWith("generate.geography.") || key.startsWith("generate.demographics.default")
          || key.startsWith("generate.providers.") || key.startsWith("generate.payers.")
          || key.startsWith("generate.names.")) {
        savedConfig.put(key, Config.get(key));
        Config.set(key, main.getProperty(key));
      }
    }
    assertEquals("GB", Config.get("generate.geography.country_code"));
    assertEquals(REGION, Config.get("generate.geography.default_state"));
    location = new Location(REGION, null);
  }

  /** Restore the US test configuration. */
  @AfterClass
  public static void restoreConfig() {
    for (Map.Entry<String, String> e : savedConfig.entrySet()) {
      if (e.getValue() == null) {
        Config.remove(e.getKey());
      } else {
        Config.set(e.getKey(), e.getValue());
      }
    }
    Provider.clear();
    PayerManager.clear();
  }

  @Before
  public void clear() {
    Provider.clear();
  }

  private static List<Provider> ofType(ProviderType type) {
    return Provider.getProviderList().stream().filter(p -> p.type == type)
        .collect(Collectors.toList());
  }

  @Test
  public void loadsOnlyUkProviderTypes() {
    Provider.loadProviders(location, random);
    assertTrue(ofType(ProviderType.HOSPITAL).size() > 100);
    assertTrue(ofType(ProviderType.PRIMARY).size() > 500);
    assertTrue(ofType(ProviderType.URGENT).size() > 20);
    for (ProviderType usOnly : new ProviderType[] {ProviderType.VETERAN, ProviderType.IHS,
        ProviderType.NURSING, ProviderType.HOSPICE, ProviderType.HOME_HEALTH}) {
      assertTrue(usOnly + " should not be loaded", ofType(usOnly).isEmpty());
    }
  }

  @Test
  public void providersHaveUkAddresses() {
    Provider.loadProviders(location, random);
    for (Provider p : Provider.getProviderList()) {
      assertEquals(p.name, "SW", p.state);
      assertNotNull(p.name + " postcode read into zip", p.zip);
      assertTrue(p.name + " " + p.zip, POSTCODE.matcher(p.zip).matches());
      double lat = p.getLonLat().getY();
      double lon = p.getLonLat().getX();
      assertTrue(p.name + " lat " + lat, lat > 49.8 && lat < 52.2);
      assertTrue(p.name + " lon " + lon, lon > -6.5 && lon < -1.4);
      assertFalse(p.name + " npi", p.npi == null || p.npi.isEmpty());
      assertFalse(p.name + " has no unmapped postcode attribute",
          p.attributes.containsKey("postcode"));
    }
  }

  @Test
  public void majorHospitalsProvideEmergencyCare() {
    Provider.loadProviders(location, random);
    List<String> emergency = ofType(ProviderType.HOSPITAL).stream()
        .filter(p -> p.hasService(EncounterType.EMERGENCY)).map(p -> p.name)
        .collect(Collectors.toList());
    assertEquals(10, emergency.size());
    assertTrue(emergency.contains("DERRIFORD HOSPITAL"));
    assertTrue(emergency.contains("GLOUCESTERSHIRE ROYAL HOSPITAL"));
    for (Provider p : ofType(ProviderType.URGENT)) {
      assertTrue(p.hasService(EncounterType.URGENTCARE));
      assertFalse(p.hasService(EncounterType.EMERGENCY));
    }
  }

  @Test
  public void cliniciansIncludeGpsAndMidwives() {
    Provider.loadProviders(location, random);
    for (Provider p : ofType(ProviderType.PRIMARY)) {
      assertEquals(5, p.clinicianMap.get(ClinicianSpecialty.GENERAL_PRACTICE).size());
      assertEquals(1, p.clinicianMap.get(ClinicianSpecialty.CERTIFIED_NURSE_MIDWIFE).size());
    }
    for (Provider p : ofType(ProviderType.HOSPITAL)) {
      assertNotNull(p.clinicianMap.get(ClinicianSpecialty.CLINICAL_NURSE_SPECIALIST));
      assertNotNull(p.clinicianMap.get(ClinicianSpecialty.CERTIFIED_NURSE_MIDWIFE));
    }
  }

  @Test
  public void missingGeneralPracticeColumnStillGetsAGp() throws IOException {
    // providers file with specialties but no GENERAL PRACTICE column (previously threw an NPE)
    Provider.loadProviders(location, "uk_providers_no_gp_column.csv", ProviderType.HOSPITAL,
        Collections.singleton(EncounterType.INPATIENT), random, false);
    List<Provider> loaded = Provider.getProviderList();
    assertEquals(1, loaded.size());
    Provider p = loaded.get(0);
    assertEquals("TR1 3LJ", p.zip);
    assertEquals(1, p.clinicianMap.get(ClinicianSpecialty.GENERAL_PRACTICE).size());
    assertEquals(2, p.clinicianMap.get(ClinicianSpecialty.CERTIFIED_NURSE_MIDWIFE).size());
    assertNull(p.cmsCategory);
  }

  @Test
  public void everyoneIsCoveredByTheNhsFrom1948() throws Exception {
    PayerManager.clear();
    PayerManager.loadPayers(location);
    List<Payer> payers = PayerManager.getAllPayers();
    assertEquals(1, payers.size());
    Payer nhs = payers.stream().filter(p -> p.getName().equals("NHS England")).findFirst().get();
    assertTrue(nhs.isGovernmentPayer());

    Person person = new Person(1L);
    person.attributes.put(Person.BIRTHDATE, Utilities.convertCalendarYearsToTime(1980));
    person.attributes.put(Person.STATE, REGION);
    person.attributes.put(Person.INCOME, 20000);
    person.attributes.put(Person.OCCUPATION_LEVEL, 0.5);
    person.attributes.put(Person.GENDER, "F");
    long time = Utilities.convertCalendarYearsToTime(2020);
    InsurancePlan plan = PayerManager.findPlan(person, EncounterType.WELLNESS, time);
    assertEquals("NHS England", plan.getPayer().getName());
    assertEquals(0, plan.getMonthlyPremium(0).signum());

    long before = Utilities.convertCalendarYearsToTime(1940);
    InsurancePlan prewar = PayerManager.findPlan(person, EncounterType.WELLNESS, before);
    assertTrue("no NHS before 1948", prewar.isNoInsurance());
  }
}
