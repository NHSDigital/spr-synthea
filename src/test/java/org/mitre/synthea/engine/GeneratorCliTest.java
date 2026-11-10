package org.mitre.synthea.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.synthea.helpers.Utilities;

/**
 * Verifies CLI generation behaviour using fresh child JVM processes with the compiled
 * classpath, the default (UK) configuration and isolated temporary directories. Child processes
 * use a fixed UTC timezone, fixed seeds and simulation dates, and one worker.
 *
 * <p>{@link #maternityPatientMatchesSnapshot()} compares a whole seeded patient with
 * {@code src/test/resources/snapshots/maternity-patient.json}. Every run writes the actual bundle
 * to {@code build/snapshots/maternity-patient.actual.json}; the snapshot is never updated
 * automatically. For an intentional behaviour change, review the differences with
 * {@code diff -u src/test/resources/snapshots/maternity-patient.json
 * build/snapshots/maternity-patient.actual.json}, then copy the actual file over the snapshot
 * and explain the change in the commit.</p>
 */
public class GeneratorCliTest {
  private static final String NHS_SYSTEM = "https://fhir.nhs.uk/Id/nhs-number";

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * Runs a tiny fixed-date/seed, single-worker CLI generation run, repeated in fresh processes,
   * verifying R4 JSON, NHS identifier/filename consistency, UUID reference closure, and ensuring
   * US insurance claims (Claim / ExplanationOfBenefit) remain disabled.
   */
  @Test
  public void fixedCohortExportsConsistentR4AndRepeats() throws Exception {
    Path first = temporaryFolder.newFolder("first").toPath();
    Path second = temporaryFolder.newFolder("second").toPath();
    runCli(first, 0);
    runCli(second, 0);
    Map<String, JsonObject> bundles = readBundles(first);
    assertEquals("Overflow is disabled: one living patient was requested", 1, bundles.size());
    assertEquals("Same seeds/dates/config should reproduce the cohort", bundles,
        readBundles(second));

    JsonObject bundle = bundles.values().iterator().next();
    assertEquals("Bundle", bundle.get("resourceType").getAsString());
    assertEquals("transaction", bundle.get("type").getAsString());
    Set<String> urls = new HashSet<>();
    JsonObject patient = null;
    for (JsonElement entry : bundle.getAsJsonArray("entry")) {
      JsonObject item = entry.getAsJsonObject();
      assertTrue("Duplicate fullUrl", urls.add(item.get("fullUrl").getAsString()));
      JsonObject resource = item.getAsJsonObject("resource");
      if ("Patient".equals(resource.get("resourceType").getAsString())) {
        assertNull("Only one Patient per bundle", patient);
        patient = resource;
      }
      assertFalse("US insurance claims must stay disabled",
          "Claim".equals(resource.get("resourceType").getAsString())
              || "ExplanationOfBenefit".equals(resource.get("resourceType").getAsString()));
    }
    assertNotNull("Patient missing", patient);
    String nhs = null;
    for (JsonElement identifier : patient.getAsJsonArray("identifier")) {
      JsonObject id = identifier.getAsJsonObject();
      if (NHS_SYSTEM.equals(id.get("system").getAsString())) {
        nhs = id.get("value").getAsString();
      }
    }
    assertNotNull("NHS identifier missing", nhs);
    assertTrue("Filename must match the exported NHS identifier",
        bundles.containsKey(nhs + ".json"));
    assertTrue("Clinical events missing", urls.size() > 1);
    checkReferences(bundle, urls, nhs);
  }

  /**
   * Generates a whole maternity patient through the real modules and compares the complete
   * lifetime R4 bundle with a reviewed baseline, not another run of the current implementation.
   * Only the build version in the Patient narrative is normalized; clinical data is unchanged.
   */
  @Test
  public void maternityPatientMatchesSnapshot() throws Exception {
    Path output = temporaryFolder.newFolder("maternity").toPath();
    runCli(output, 0, "-ps", "4242", "-m", String.join(File.pathSeparator,
        "contraceptives", "contraceptive_maintenance", "female_reproduction",
        "sexual_activity", "pregnancy", "wellness_encounters"),
        "--exporter.years_of_history=0");
    Map<String, JsonObject> bundles = readBundles(output);
    assertEquals("One complete patient is expected", 1, bundles.size());
    JsonObject actual = bundles.values().iterator().next();
    for (JsonElement entry : actual.getAsJsonArray("entry")) {
      JsonObject resource = entry.getAsJsonObject().getAsJsonObject("resource");
      if ("Patient".equals(resource.get("resourceType").getAsString())) {
        JsonObject narrative = resource.getAsJsonObject("text");
        String version = "Version identifier: " + Utilities.SYNTHEA_VERSION + " . ";
        String text = narrative.get("div").getAsString();
        assertTrue("Expected build provenance in Patient narrative", text.contains(version));
        narrative.addProperty("div", text.replace(version,
            "Version identifier: SNAPSHOT_VERSION . "));
      }
    }

    Path candidate = Paths.get("build", "snapshots", "maternity-patient.actual.json");
    Files.createDirectories(candidate.getParent());
    Files.writeString(candidate, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
        .create().toJson(actual) + "\n", StandardCharsets.UTF_8);
    try (InputStream snapshot =
        getClass().getResourceAsStream("/snapshots/maternity-patient.json")) {
      assertNotNull("Snapshot missing. Review the candidate at " + candidate, snapshot);
      JsonElement expected = JsonParser.parseString(
          new String(snapshot.readAllBytes(), StandardCharsets.UTF_8));
      assertTrue("Patient snapshot changed. Compare src/test/resources/snapshots/"
          + "maternity-patient.json with " + candidate + "; see docs/devs/testing.md",
          expected.equals(actual));
    }
  }

  /**
   * Verifies CLI failure when the NHS-number range is exhausted: nonzero exit and no successful
   * completion summary. Partial patient files can remain; loaders must reject a failed run's
   * output.
   */
  @Test
  public void exhaustedNhsRangeFailsTheProcessInsteadOfReportingCompletion() throws Exception {
    Path output = temporaryFolder.newFolder("exhaustion").toPath();
    String log = runCli(output, 1, "--generate.patient.nhs_number.range.start=900000000",
        "--generate.patient.nhs_number.range.end=900000000", "-p", "3");
    assertTrue(log, log.contains("Configured NHS number range exhausted"));
    assertTrue(log, log.contains("tasks; output is incomplete."));
    assertFalse("An incomplete run must not report successful completion",
        log.contains("Records:"));
  }

  private String runCli(Path output, int expectedExit, String... extraArgs) throws Exception {
    List<String> args = new ArrayList<>(Arrays.asList(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx1g", "-Duser.timezone=UTC", "-Duser.language=en", "-Duser.country=GB", "-cp",
        System.getProperty("synthea.test.classpath"), "App",
        "-s", "4242", "-cs", "4242", "-p", "1", "-o", "false",
        "-r", "20251001", "-e", "20251001", "-a", "30-30", "-g", "F",
        "--generate.thread_pool_size=1", "--generate.only_alive_patients=true",
        "--exporter.years_of_history=1", "--exporter.baseDirectory=" + output));
    args.addAll(Arrays.asList(extraArgs));
    File logFile = temporaryFolder.newFile();
    Process process = new ProcessBuilder(args).redirectErrorStream(true)
        .redirectOutput(logFile).start();
    try {
      assertTrue("Generator did not finish within 60 seconds",
          process.waitFor(60, TimeUnit.SECONDS));
      String log = Files.readString(logFile.toPath(), StandardCharsets.UTF_8);
      if (expectedExit == 0) {
        assertEquals(log, 0, process.exitValue());
        assertTrue(log, log.contains("Records: total=1, alive=1, dead=0"));
      } else {
        assertNotEquals(log, 0, process.exitValue());
      }
      return log;
    } finally {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  private Map<String, JsonObject> readBundles(Path output) throws Exception {
    Map<String, JsonObject> bundles = new HashMap<>();
    try (Stream<Path> files = Files.list(output.resolve("fhir"))) {
      // Skip the shared hospitalInformation/practitionerInformation bundles.
      for (Path file : files.filter(p -> p.toString().endsWith(".json")
          && !p.getFileName().toString().matches("(hospital|practitioner)Information.*"))
          .collect(Collectors.toList())) {
        bundles.put(file.getFileName().toString(),
            JsonParser.parseString(Files.readString(file)).getAsJsonObject());
      }
    }
    return bundles;
  }

  private void checkReferences(JsonElement element, Set<String> urls, String nhs) {
    if (element.isJsonArray()) {
      element.getAsJsonArray().forEach(child -> checkReferences(child, urls, nhs));
    } else if (element.isJsonObject()) {
      JsonObject object = element.getAsJsonObject();
      if (object.has("reference")) {
        String reference = object.get("reference").getAsString();
        if (reference.startsWith("urn:uuid:")) {
          assertTrue("Unresolved bundle reference: " + reference, urls.contains(reference));
        } else {
          // Providers are external dependencies of the current transaction exporter.
          assertTrue("Unexpected external reference: " + reference,
              reference.startsWith("Organization?identifier=")
                  || reference.startsWith("Practitioner?identifier="));
        }
      }
      if (object.has("identifier") && object.get("identifier").isJsonObject()) {
        JsonObject id = object.getAsJsonObject("identifier");
        if (id.has("system") && NHS_SYSTEM.equals(id.get("system").getAsString())) {
          assertEquals("Logical subject must identify this patient", nhs,
              id.get("value").getAsString());
        }
      }
      object.entrySet().forEach(entry -> checkReferences(entry.getValue(), urls, nhs));
    }
  }
}
