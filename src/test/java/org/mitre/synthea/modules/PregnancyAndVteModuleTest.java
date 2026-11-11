package org.mitre.synthea.modules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.withSettings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.engine.Module;
import org.mitre.synthea.engine.State;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.ClinicianSpecialty;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.geography.Location;
import org.mockito.Mockito;

/**
 * Tests for the venous thromboembolism module and the UK pregnancy pathways
 * (midwife-led care, VTE prevention, hyperemesis gravidarum and gestational diabetes).
 */
public class PregnancyAndVteModuleTest {
  private static final String HISTORY_OF_THROMBOSIS = "275546001";
  private static final String DVT = "128053003";
  private static final String APIXABAN = "1364445";

  private Person person;
  private long time;

  /**
   * Create an adult woman with mock providers and no insurance.
   */
  @Before
  public void setup() throws IOException {
    time = System.currentTimeMillis();
    person = new Person(0L);
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.BIRTHDATE, time - Utilities.convertTime("years", 30));
    person.history = new LinkedList<>();
    Provider mock = Mockito.mock(Provider.class, withSettings().serializable());
    Mockito.when(mock.getResourceID()).thenReturn("Mock-UUID");
    for (EncounterType type : EncounterType.values()) {
      person.setProvider(type, mock);
    }
    PayerManager.loadPayers(new Location(Config.get("test_state.default", "Massachusetts"), null));
    person.coverage.setPlanToNoInsurance(time - Utilities.convertTime("years", 30));
    person.coverage.setPlanToNoInsurance(time + Utilities.convertTime("years", 20));
    Map<Integer, Double> qolsByYear = new HashMap<>();
    qolsByYear.put(Utilities.getYear(time) - 1, 1.0);
    person.attributes.put(QualityOfLifeModule.QOLS, qolsByYear);
  }

  private void processStates(Module module, String... names) {
    for (String name : names) {
      State state = module.getState(name);
      assertNotNull(name, state);
      assertTrue(name, state.process(person, time));
      person.history.add(0, state);
    }
  }

  @Test
  public void testVteIsTreatedThenRecordedAsHistoryOfThrombosis() {
    Module vte = Module.getModuleByPath("vte").clone();
    processStates(vte, "DVT", "VTE_Encounter", "Anticoagulation");
    assertTrue(person.record.conditionActive(DVT));
    assertTrue(person.record.medicationActive(APIXABAN));
    processStates(vte, "End_VTE_Encounter");

    time += Utilities.convertTime("months", 3);
    processStates(vte, "VTE_Follow_Up_Encounter", "End_Anticoagulation", "VTE_Resolved",
        "History_Of_Thrombosis", "End_VTE_Follow_Up_Encounter");
    assertFalse(person.record.conditionActive(DVT));
    assertFalse(person.record.medicationActive(APIXABAN));
    assertTrue(person.record.conditionActive(HISTORY_OF_THROMBOSIS));
    assertNotNull(person.attributes.get("history_of_thrombosis"));
  }

  @Test
  public void testHistoryOfThrombosisRoutesPregnancyToVteRiskAssessment() {
    State router = Module.getModuleByPath("pregnancy").clone().getState("Possible_Pregnancy");
    assertEquals("Become_Pregnant", router.transition(person, time));
    person.record.conditionStart(time, HISTORY_OF_THROMBOSIS);
    assertEquals("VTE_RISK_6_Week_Encounter", router.transition(person, time));
  }

  @Test
  public void testPregnancyCareIsMidwifeLedExceptHyperemesisAtUrgentCare() throws IOException {
    assertEquals(ClinicianSpecialty.CERTIFIED_NURSE_MIDWIFE,
        Module.getModuleByPath("pregnancy").specialty);
    JsonObject hg = pregnancyStates().getAsJsonObject("HG_Encounter");
    assertEquals(ClinicianSpecialty.GENERAL_PRACTICE, hg.get("specialty").getAsString());
  }

  @Test
  public void testPregnancyLimitedEntriesEndOnEveryPath() throws IOException {
    JsonObject states = pregnancyStates();
    // Every path from the start of these entries back to the next pregnancy must end them.
    assertAllPathsPassThrough(states, "VTE_CarePlan", "End_VTE_CarePlan");
    assertAllPathsPassThrough(states, "VTE_At_High_Risk", "End_VTE_At_High_Risk");
    assertAllPathsPassThrough(states, "GDM_Onset", "End_Pregnancy_GDM");
    assertAllPathsPassThrough(states, "HG_Onset", "End_Pregnancy_HG");
  }

  private static JsonObject pregnancyStates() throws IOException {
    String json = Utilities.readResource("modules/pregnancy.json");
    return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("states");
  }

  /**
   * Assert that Become_Pregnant_Guard (the start of the next pregnancy) cannot be reached
   * from {@code start} without passing through {@code required}.
   */
  private static void assertAllPathsPassThrough(JsonObject states, String start,
      String required) {
    assertTrue(states.has(start));
    assertTrue(states.has(required));
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      String name = queue.poll();
      if (name.equals(required) || !seen.add(name)) {
        continue;
      }
      assertFalse(start + " reaches the next pregnancy without " + required,
          name.equals("Become_Pregnant_Guard"));
      collectTransitions(states.get(name), queue);
    }
  }

  private static void collectTransitions(JsonElement element, Deque<String> out) {
    if (element.isJsonObject()) {
      for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
        if ((e.getKey().equals("transition") || e.getKey().equals("direct_transition"))
            && e.getValue().isJsonPrimitive()) {
          out.add(e.getValue().getAsString());
        } else {
          collectTransitions(e.getValue(), out);
        }
      }
    } else if (element.isJsonArray()) {
      element.getAsJsonArray().forEach(child -> collectTransitions(child, out));
    }
  }
}
