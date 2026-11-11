package org.mitre.synthea.helpers;

import org.mitre.synthea.world.agents.Person;

/**
 * Issues a nation's personal identifier, e.g. the NHS number in England or the Social Security
 * Number in the US.
 *
 * <p>Create one per simulation run with {@code Nation.newNationalIdGenerator()} and pass it to
 * {@code LifecycleModule.birth()}, so that identifiers are unique across the run. Another
 * country adds its identifier by implementing this interface and returning it from its
 * {@code Nation}.</p>
 */
public interface NationalIdGenerator {

  /**
   * The person attribute that holds this identifier.
   * @return e.g. {@link Person#IDENTIFIER_NHS_NUMBER}.
   */
  String attribute();

  /**
   * The FHIR identifier system (URI) of this identifier.
   * @return e.g. https://fhir.nhs.uk/Id/nhs-number.
   */
  String system();

  /**
   * Issue a new identifier for a person.
   * @param person the person.
   * @return the identifier.
   */
  String generate(Person person);

  /**
   * Record an identifier that was supplied rather than generated (e.g. by a fixed record), so
   * that it is not issued to anyone else.
   * @param id the identifier.
   */
  void reserve(String id);
}
