package org.mitre.synthea.modules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.withSettings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.engine.Module;
import org.mitre.synthea.engine.State;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.ClinicianSpecialty;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Location;
import org.mockito.Mockito;

/**
 * Tests for the NICE NG136 hypertension medication submodule used for UK populations.
 */
public class HypertensionMedicationUkTest {
  private static final String SUBMODULE = "medications/hypertension_medication_uk";
  private static final String RAMIPRIL = "198188";
  private static final String CANDESARTAN = "153823";
  private static final String AMLODIPINE = "197361";
  private static final String INDAPAMIDE = "197816";
  private static final String LABETALOL = "896758";
  private static final Set<String> STEP_4 = Set.of("313096", "197627", "854905");

  private Person person;
  private long time;

  /**
   * Create an adult with a mock provider and no hypertension treatment.
   */
  @Before
  public void setup() throws IOException {
    time = System.currentTimeMillis();
    person = new Person(0L);
    person.attributes.put(Person.GENDER, "M");
    person.attributes.put(Person.RACE, "white");
    person.attributes.put(Person.COUNTRY, "GB");
    person.history = new LinkedList<>();
    Provider mock = Mockito.mock(Provider.class, withSettings().serializable());
    Mockito.when(mock.getResourceID()).thenReturn("Mock-UUID");
    person.setProvider(EncounterType.WELLNESS, mock);
    setAge(45);
    PayerManager.loadPayers(new Location(Config.get("test_state.default", "Massachusetts"), null));
    person.coverage.setPlanToNoInsurance(time - Utilities.convertTime("years", 60));
    person.coverage.setPlanToNoInsurance(time + Utilities.convertTime("years", 20));
    Map<Integer, Double> qolsByYear = new HashMap<>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);
    person.setVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, 150);
  }

  private void setAge(int years) {
    person.attributes.put(Person.BIRTHDATE, time - Utilities.convertTime("years", years));
    person.attributes.remove(Person.BIRTHDATE_AS_LOCALDATE);
  }

  /** Run the submodule once inside a wellness encounter, as the hypertension module does. */
  private void prescribe() {
    time += Utilities.convertTime("months", 1);
    EncounterModule.createEncounter(person, time, EncounterType.WELLNESS,
        ClinicianSpecialty.GENERAL_PRACTICE, EncounterModule.ENCOUNTER_CHECKUP,
        EncounterModule.NAME);
    Module submodule = Module.getModuleByPath(SUBMODULE);
    assertNotNull(submodule);
    assertTrue(submodule.process(person, time));
    person.attributes.remove(submodule.submoduleName);
    person.record.encounterEnd(time, EncounterType.WELLNESS);
  }

  private Set<String> activeMedications() {
    return person.chronicMedications.keySet();
  }

  private int step() {
    return ((Number) person.attributes.get("hypertension_medication_step")).intValue();
  }

  @Test
  public void under55NonBlackStartsOnAceInhibitor() {
    prescribe();
    assertEquals(1, step());
    assertEquals(Set.of(RAMIPRIL), activeMedications());
    assertEquals(false, person.attributes.get("hypertension_severe"));
    Medication med = (Medication) person.attributes.get("hypertension_medication");
    assertEquals(RAMIPRIL, med.codes.get(0).code);
  }

  @Test
  public void severeHypertensionIsFlaggedForTheParentModule() {
    person.setVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, 170);
    prescribe();
    assertEquals(true, person.attributes.get("hypertension_severe"));
  }

  @Test
  public void aged55OrOverStartsOnCalciumChannelBlocker() {
    setAge(60);
    prescribe();
    assertEquals(Set.of(AMLODIPINE), activeMedications());
  }

  @Test
  public void blackAfricanOrCaribbeanStartsOnCalciumChannelBlocker() {
    person.attributes.put(Person.RACE, "black");
    prescribe();
    assertEquals(Set.of(AMLODIPINE), activeMedications());
  }

  @Test
  public void diabetesStartsOnAceInhibitorRegardlessOfAge() {
    setAge(70);
    person.attributes.put("diabetes", true);
    prescribe();
    assertEquals(Set.of(RAMIPRIL), activeMedications());
  }

  @Test
  public void blackWithDiabetesGetsArbInsteadOfAceInhibitor() {
    person.attributes.put(Person.RACE, "black");
    person.attributes.put("diabetes", true);
    prescribe();
    assertEquals(Set.of(CANDESARTAN), activeMedications());
  }

  @Test
  public void pregnancyUsesLabetalolAndDoesNotEscalate() {
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put("pregnant", true);
    prescribe();
    prescribe();
    prescribe();
    assertEquals(Set.of(LABETALOL), activeMedications());
  }

  @Test
  public void stepsThroughTripleTherapyToResistantHypertension() {
    prescribe();
    prescribe();
    assertEquals(2, step());
    assertEquals(2, activeMedications().size());
    assertTrue(activeMedications().contains(RAMIPRIL));

    prescribe();
    assertEquals(3, step());
    assertEquals(Set.of(RAMIPRIL, AMLODIPINE, INDAPAMIDE), activeMedications());

    prescribe();
    assertEquals(4, step());
    assertEquals(4, activeMedications().size());
    Set<String> added = activeMedications().stream()
        .filter(STEP_4::contains).collect(Collectors.toSet());
    assertEquals(1, added.size());

    // Beyond step 4 the person is referred rather than given more drugs
    prescribe();
    assertEquals(5, step());
    assertEquals(4, activeMedications().size());
  }

  @Test
  public void allUkDrugsHaveBloodPressureImpacts() throws Exception {
    List<LinkedHashMap<String, String>> drugs =
        SimpleCSV.parse(Utilities.readResource("htn_drugs.csv"));
    Set<String> codes = drugs.stream().map(d -> d.get("RxNorm")).collect(Collectors.toSet());
    JsonObject states = JsonParser.parseString(
        Utilities.readResource("modules/" + SUBMODULE + ".json"))
        .getAsJsonObject().getAsJsonObject("states");
    int orders = 0;
    for (String name : states.keySet()) {
      JsonObject state = states.getAsJsonObject(name);
      if ("MedicationOrder".equals(state.get("type").getAsString())) {
        orders++;
        String code = state.getAsJsonArray("codes").get(0).getAsJsonObject()
            .get("code").getAsString();
        assertTrue("htn_drugs.csv is missing " + code, codes.contains(code));
      }
    }
    assertTrue(orders > 0);
    for (LinkedHashMap<String, String> drug : drugs) {
      assertFalse("tab in " + drug.get("Display"), drug.get("Display").contains("\t"));
    }
  }

  @Test
  public void hypertensionModuleRoutesByCountry() {
    Module hypertension = Module.getModuleByPath("hypertension");
    for (String name : List.of("Prescribe_Medication", "Prescribe_Medication_2",
        "Prescribe_Medication_3")) {
      State router = hypertension.getState(name);
      State uk = hypertension.getState(name + "_UK");
      State us = hypertension.getState(name + "_US");
      assertNotNull(router);
      assertTrue(uk instanceof State.CallSubmodule);
      assertTrue(us instanceof State.CallSubmodule);

      person.attributes.put(Person.COUNTRY, "GB");
      assertEquals(name + "_UK", router.transition(person, time));
      person.attributes.put(Person.COUNTRY, "US");
      assertEquals(name + "_US", router.transition(person, time));
    }
  }
}
