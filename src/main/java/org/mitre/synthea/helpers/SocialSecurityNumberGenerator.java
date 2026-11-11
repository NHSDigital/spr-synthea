package org.mitre.synthea.helpers;

import org.mitre.synthea.world.agents.Person;

/**
 * US Social Security Numbers: 999-xx-xxxx, which are never issued, drawn from the person's
 * random stream. Uniqueness checking is not implemented yet.
 */
public class SocialSecurityNumberGenerator implements NationalIdGenerator {
  public static final String SSN_SYSTEM = "http://hl7.org/fhir/sid/us-ssn";

  /**
   * A random 999-xx-xxxx Social Security Number for a person.
   * @param person the person, whose random stream is used.
   * @return the SSN.
   */
  public static String randomSsn(Person person) {
    return "999-" + ((person.randInt(99 - 10 + 1) + 10)) + "-"
        + ((person.randInt(9999 - 1000 + 1) + 1000));
  }

  @Override
  public String attribute() {
    return Person.IDENTIFIER_SSN;
  }

  @Override
  public String system() {
    return SSN_SYSTEM;
  }

  @Override
  public String generate(Person person) {
    return randomSsn(person);
  }

  @Override
  public void reserve(String id) {
    // reserving SSNs is not implemented yet
  }
}
