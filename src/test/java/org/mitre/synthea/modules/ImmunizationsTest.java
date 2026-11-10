package org.mitre.synthea.modules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;

public class ImmunizationsTest {
  private static final String UK_SCHEDULE = "geography/england/immunization_schedule.json";
  private static final String CVX = "http://hl7.org/fhir/sid/cvx";

  @After
  public void restoreSchedule() {
    Config.set(Immunizations.SCHEDULE_CONFIG, Immunizations.DEFAULT_SCHEDULE);
  }

  @Test
  public void testMaximumDoses() {
    // "52" is the Hep A Adult vaccine. It requires two doses.
    int doses = Immunizations.getMaximumDoses("52");
    assertEquals(2, doses);
  }

  @Test
  public void testMaximumDosesNothing() {
    int doses = Immunizations.getMaximumDoses(null);
    assertEquals(1, doses);
  }

  @Test
  @SuppressWarnings("rawtypes")
  public void testUkScheduleIsWellFormed() {
    Config.set(Immunizations.SCHEDULE_CONFIG, UK_SCHEDULE);
    Map<String, Map> schedule = Immunizations.getSchedule();
    assertTrue(schedule.containsKey("dtap_ipv_hib_hepb"));
    assertFalse("US-only vaccines should not be in the UK schedule",
        schedule.containsKey("var") || schedule.containsKey("hepa"));
    for (Map.Entry<String, Map> entry : schedule.entrySet()) {
      Map code = (Map) entry.getValue().get("code");
      assertEquals(entry.getKey(), CVX, code.get("system"));
      assertFalse(entry.getKey(), code.get("display").toString().isEmpty());
      assertFalse(entry.getKey(), ((List) entry.getValue().get("at_months")).isEmpty());
    }
    // the 6-in-1 primary course is three doses
    assertEquals(3, Immunizations.getMaximumDoses("146"));
  }

  @Test
  public void testScheduleSwitchesWithConfig() {
    assertEquals(2, Immunizations.getMaximumDoses("52"));
    Config.set(Immunizations.SCHEDULE_CONFIG, UK_SCHEDULE);
    // Hep A is not part of the UK routine schedule
    assertEquals(1, Immunizations.getMaximumDoses("52"));
  }

  /**
   * Simulate monthly GP visits for a child and return the age in months at which each
   * immunization was given.
   */
  private static Map<String, List<Integer>> simulate(LocalDate birth, int months) {
    Person person = new Person(0L);
    long birthTime = birth.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    person.attributes.put(Person.BIRTHDATE, birthTime);
    Map<String, List<Long>> given = new HashMap<>();
    Map<String, List<Integer>> ages = new HashMap<>();
    for (int m = 0; m <= months; m++) {
      long time = birth.plusMonths(m).plusDays(3)
          .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
      for (String imm : Immunizations.getSchedule().keySet()) {
        if (Immunizations.immunizationDue(imm, person, time, given) > 0) {
          given.get(imm).add(time);
          ages.computeIfAbsent(imm, k -> new ArrayList<>()).add(person.ageInMonths(time));
        }
      }
    }
    return ages;
  }

  @Test
  public void testUkChildhoodScheduleFor2021Birth() {
    Config.set(Immunizations.SCHEDULE_CONFIG, UK_SCHEDULE);
    Map<String, List<Integer>> ages = simulate(LocalDate.of(2021, 1, 10), 48);

    assertEquals(List.of(2, 3, 4), ages.get("dtap_ipv_hib_hepb"));
    assertEquals(List.of(2, 3), ages.get("rotavirus"));
    assertEquals(List.of(2, 4, 12), ages.get("menb"));
    assertEquals(List.of(3, 12), ages.get("pcv13_1p1"));
    assertEquals(List.of(12), ages.get("hib_booster"));
    assertEquals(List.of(12), ages.get("menc"));
    assertEquals(List.of(12, 40), ages.get("mmr"));
    assertEquals(List.of(40), ages.get("dtap_ipv"));
    // superseded vaccines are not given
    assertFalse(ages.containsKey("dtap_ipv_hib"));
    assertFalse(ages.containsKey("pcv13"));
    assertFalse(ages.containsKey("pcv7"));
    assertFalse(ages.containsKey("menc_infant"));
  }

  @Test
  public void testLastAvailableStopsWithdrawnVaccine() {
    Config.set(Immunizations.SCHEDULE_CONFIG, UK_SCHEDULE);
    // Born mid-2016: primary course completes before the 6-in-1 replaced the 5-in-1
    Map<String, List<Integer>> ages = simulate(LocalDate.of(2016, 5, 1), 6);
    assertEquals(List.of(2, 3, 4), ages.get("dtap_ipv_hib"));
    assertFalse(ages.containsKey("dtap_ipv_hib_hepb"));
    assertEquals(List.of(2, 4), ages.get("pcv13"));

    // Born in 2018: only the 6-in-1 is available
    ages = simulate(LocalDate.of(2018, 3, 1), 6);
    assertFalse(ages.containsKey("dtap_ipv_hib"));
    assertEquals(List.of(2, 3, 4), ages.get("dtap_ipv_hib_hepb"));
  }

  @Test
  public void testFirstAvailableStillApplies() {
    Config.set(Immunizations.SCHEDULE_CONFIG, UK_SCHEDULE);
    long before = Utilities.convertCalendarYearsToTime(2012);
    Person person = new Person(0L);
    person.attributes.put(Person.BIRTHDATE, before - Utilities.convertTime("months", 2));
    assertEquals(-1, Immunizations.immunizationDue("rotavirus", person, before,
        new HashMap<>()));
  }
}
