package org.mitre.synthea.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.engine.State;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.NhsNumberGenerator;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;

/**
 * Checks the UK Core flavoured FHIR R4 export of England (South West) patients.
 */
public class FhirR4UkCoreTest {
  private static final Map<String, String> savedConfig = new HashMap<>();
  private static boolean savedUsCore;
  private static boolean savedUkCore;
  private static boolean savedPhysiology;
  private static List<Person> people;

  /** Switch to the main (UK) defaults and generate a few patients. */
  @BeforeClass
  public static void generateUkPatients() throws Exception {
    TestHelper.loadTestProperties();
    TestHelper.exportOff();
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
    savedUsCore = FhirR4.USE_US_CORE_IG;
    savedUkCore = FhirR4.USE_UK_CORE_IG;
    savedPhysiology = State.ENABLE_PHYSIOLOGY_STATE;
    FhirR4.USE_US_CORE_IG = false;
    FhirR4.USE_UK_CORE_IG = true;
    FhirR4.reloadIncludeExclude();
    Provider.clear();
    PayerManager.clear();

    Generator.GeneratorOptions options = new Generator.GeneratorOptions();
    options.state = "South West";
    options.population = 4;
    options.seed = 20240501L;
    options.clinicianSeed = 20240501L;
    options.overflow = false;
    Generator generator = new Generator(options);
    people = new ArrayList<>();
    for (int i = 0; i < options.population; i++) {
      people.add(generator.generatePerson(i));
    }
  }

  /** Restore the US test configuration. */
  @AfterClass
  public static void restore() {
    savedConfig.forEach((key, value) -> {
      if (value == null) {
        Config.remove(key);
      } else {
        Config.set(key, value);
      }
    });
    FhirR4.USE_US_CORE_IG = savedUsCore;
    FhirR4.USE_UK_CORE_IG = savedUkCore;
    State.ENABLE_PHYSIOLOGY_STATE = savedPhysiology;
    Provider.clear();
    PayerManager.clear();
  }

  private static Patient patientOf(Bundle bundle) {
    return (Patient) bundle.getEntry().get(0).getResource();
  }

  @Test
  public void patientHasVerifiedNhsNumber() {
    for (Person person : people) {
      Patient patient = patientOf(FhirR4.convertToFHIR(person, System.currentTimeMillis()));
      List<Identifier> nhs = patient.getIdentifier().stream()
          .filter(i -> FhirR4.NHS_NUMBER_SYSTEM.equals(i.getSystem()))
          .collect(Collectors.toList());
      assertEquals(1, nhs.size());
      String value = nhs.get(0).getValue();
      assertEquals(person.attributes.get(Person.IDENTIFIER_NHS_NUMBER), value);
      assertTrue(value, NhsNumberGenerator.isValid(value));
      Extension status = nhs.get(0)
          .getExtensionByUrl(FhirR4.UK_CORE_NHS_NUMBER_VERIFICATION_STATUS);
      assertNotNull(status);
      Coding coding = ((CodeableConcept) status.getValue()).getCodingFirstRep();
      assertEquals(FhirR4.UK_CORE_NHS_NUMBER_VERIFICATION_STATUS_SYSTEM, coding.getSystem());
      assertEquals("01", coding.getCode());
      assertFalse("no US SSN in UK Core export", patient.getIdentifier().stream()
          .anyMatch(i -> "http://hl7.org/fhir/sid/us-ssn".equals(i.getSystem())));
    }
  }

  @Test
  public void patientHasUkDemographics() {
    for (Person person : people) {
      Patient patient = patientOf(FhirR4.convertToFHIR(person, System.currentTimeMillis()));
      Extension ethnic = patient.getExtensionByUrl(FhirR4.UK_CORE_ETHNIC_CATEGORY);
      assertNotNull(ethnic);
      Coding coding = ((CodeableConcept) ethnic.getValue()).getCodingFirstRep();
      assertEquals(FhirR4.UK_CORE_ETHNIC_CATEGORY_SYSTEM, coding.getSystem());
      assertTrue(coding.getCode(), coding.getCode().matches("[A-HJ-NPRS]"));
      assertNotNull(coding.getDisplay());
      assertTrue("no US Core race extension", patient.getExtension().stream()
          .noneMatch(e -> e.getUrl().contains("us-core")));
      assertEquals("GB", patient.getAddressFirstRep().getCountry());
      String language = patient.getCommunicationFirstRep().getLanguage().getCodingFirstRep()
          .getCode();
      assertFalse(language, "en-US".equals(language));
    }
  }

  @Test
  public void patientReferencesCarryNhsNumber() {
    for (Person person : people) {
      Bundle bundle = FhirR4.convertToFHIR(person, System.currentTimeMillis());
      String patientUrl = bundle.getEntry().get(0).getFullUrl();
      String nhsNumber = (String) person.attributes.get(Person.IDENTIFIER_NHS_NUMBER);
      int count = 0;
      for (BundleEntryComponent entry : bundle.getEntry().subList(1, bundle.getEntry().size())) {
        for (Reference ref : FhirR4.getContext().newTerser()
            .getAllPopulatedChildElementsOfType(entry.getResource(), Reference.class)) {
          if (patientUrl.equals(ref.getReference())) {
            assertEquals(FhirR4.NHS_NUMBER_SYSTEM, ref.getIdentifier().getSystem());
            assertEquals(nhsNumber, ref.getIdentifier().getValue());
            count++;
          }
        }
      }
      assertTrue(count > 0);
    }
  }

  @Test
  public void ukCoreExportIsValidFhirR4() throws Exception {
    ValidationResources validator = ValidationResources.forR4(null);
    List<String> errors = new ArrayList<>();
    for (Person person : people) {
      Bundle bundle = FhirR4.convertToFHIR(person, System.currentTimeMillis());
      for (BundleEntryComponent entry : bundle.getEntry()) {
        ValidationResult result = validator.validateR4(entry.getResource());
        for (SingleValidationMessage message : result.getMessages()) {
          if (message.getSeverity() == ResultSeverityEnum.ERROR
              || message.getSeverity() == ResultSeverityEnum.FATAL) {
            errors.add(entry.getResource().fhirType() + ": " + message.getMessage());
          }
        }
      }
    }
    assertTrue(String.join("\n", errors), errors.isEmpty());
  }

  /**
   * A Procedure that names a care plan code is linked to the active CarePlan with that code,
   * and the CarePlan keeps its title.
   */
  @Test
  public void procedureIsBasedOnActiveCarePlanWithTitle() {
    Person person = people.get(0);
    long start = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    HealthRecord.Encounter initial = person.record.encounterStart(start,
        EncounterType.AMBULATORY);
    initial.provider = person.record.encounters.get(0).provider;
    Code planCode = new Code("SNOMED-CT", "787331000000107",
        "Venous thromboembolism prevention management (procedure)");
    HealthRecord.CarePlan plan = person.record.careplanStart(start, planCode.code);
    plan.codes.add(planCode);
    plan.title = "Antenatal VTE prophylaxis care plan";
    person.record.encounterEnd(start + 600000L, EncounterType.AMBULATORY);

    HealthRecord.Encounter followUp = person.record.encounterStart(start + 86400000L,
        EncounterType.AMBULATORY);
    followUp.provider = initial.provider;
    HealthRecord.Procedure assessment = person.record.procedure(followUp.start, "1145072001");
    assessment.codes.add(new Code("SNOMED-CT", "1145072001", "VTE risk assessment"));
    assessment.codesBasedOn.add(planCode);
    person.record.encounterEnd(followUp.start + 600000L, EncounterType.AMBULATORY);

    Bundle bundle = FhirR4.convertToFHIR(person, followUp.stop);
    BundleEntryComponent planEntry = bundle.getEntry().stream()
        .filter(e -> e.getResource().getIdElement().getIdPart().equals(plan.uuid.toString()))
        .findFirst().get();
    Procedure procedure = (Procedure) bundle.getEntry().stream()
        .filter(e -> e.getResource().getIdElement().getIdPart()
            .equals(assessment.uuid.toString()))
        .findFirst().get().getResource();
    assertEquals(plan.title, ((CarePlan) planEntry.getResource()).getTitle());
    assertEquals(1, procedure.getBasedOn().size());
    assertEquals(planEntry.getFullUrl(), procedure.getBasedOnFirstRep().getReference());
    assertEquals(person.attributes.get(Person.IDENTIFIER_NHS_NUMBER),
        procedure.getSubject().getIdentifier().getValue());
  }

  /** Medications without dosage instructions must not break the dosage text. */
  @Test
  public void medicationWithEmptyInstructionsExports() {
    Person person = new Person(1L);
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.FIRST_LANGUAGE, "klingon");
    person.attributes.put(Person.RACE, "white");
    person.attributes.put(Person.ETHNICITY, "nonhispanic");
    person.attributes.put(Person.ETHNIC_CATEGORY, "A");
    person.attributes.put(Person.IDENTIFIER_NHS_NUMBER, "9000000009");
    person.attributes.put(Person.BIRTHDATE, Instant.parse("2023-06-01T00:00:00Z").toEpochMilli());
    person.history = new LinkedList<>();
    Provider provider = TestHelper.buildMockProvider();
    long time = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    person.coverage.setPlanToNoInsurance(time);
    HealthRecord.Encounter encounter = person.record.encounterStart(time,
        EncounterType.AMBULATORY);
    encounter.provider = provider;
    HealthRecord.Medication med = person.record.medicationStart(time, "313782", false);
    med.codes.add(new Code("RxNorm", "313782", "Acetaminophen 325 MG Oral Tablet"));
    JsonObject dosage = new JsonObject();
    dosage.addProperty("amount", 1);
    dosage.addProperty("frequency", 4);
    dosage.addProperty("period", 1);
    dosage.addProperty("unit", "days");
    JsonObject prescription = new JsonObject();
    prescription.add("dosage", dosage);
    prescription.add("instructions", new JsonArray());
    med.prescriptionDetails = prescription;
    person.record.encounterEnd(time + 600000L, EncounterType.AMBULATORY);

    Bundle bundle = FhirR4.convertToFHIR(person, time + 600000L);
    Patient patient = patientOf(bundle);
    assertEquals("unknown language falls back to English", "en-GB",
        patient.getCommunicationFirstRep().getLanguage().getCodingFirstRep().getCode());
    assertTrue(bundle.getEntry().stream()
        .anyMatch(e -> e.getResource() instanceof org.hl7.fhir.r4.model.MedicationRequest));
  }
}
