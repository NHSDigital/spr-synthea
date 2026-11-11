package org.mitre.synthea.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ca.uhn.fhir.parser.IParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.helpers.Config;

public class FhirCompressedExportTest {
  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private File outputFolder;

  /**
   * Configure FHIR-only export into a temporary folder.
   */
  @Before
  public void setup() throws Exception {
    TestHelper.exportOff();
    TestHelper.loadTestProperties();
    Generator.DEFAULT_STATE = Config.get("test_state.default", "Massachusetts");
    Config.set("exporter.fhir.export", "true");
    Config.set("exporter.split_records", "false");
    outputFolder = tempFolder.newFolder();
    Config.set("exporter.baseDirectory", outputFolder.toString());
  }

  /**
   * Restore the defaults so later tests are unaffected.
   */
  @After
  public void tearDown() {
    Config.set("exporter.fhir.compress_data", "false");
    Config.set("exporter.fhir.bulk_data", "false");
    Config.set("exporter.fhir.export", "false");
  }

  private List<File> generateAndList(int people) {
    Generator generator = new Generator(people);
    generator.options.overflow = false;
    for (int i = 0; i < people; i++) {
      generator.generatePerson(i);
    }
    File fhir = new File(outputFolder, "fhir");
    assertTrue(fhir.isDirectory());
    return Arrays.asList(fhir.listFiles());
  }

  private static List<String> readGzLines(File file) throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        new GZIPInputStream(Files.newInputStream(file.toPath())), StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.toList());
    }
  }

  @Test
  public void testCompressedBundleExport() throws Exception {
    Config.set("exporter.fhir.compress_data", "true");
    int people = 2;
    List<File> files = generateAndList(people);

    assertEquals(people, files.size());
    IParser parser = FhirR4.getContext().newJsonParser();
    for (File file : files) {
      assertTrue(file.getName(), file.getName().endsWith(".json.gz"));
      String json = String.join("\n", readGzLines(file));
      Bundle bundle = parser.parseResource(Bundle.class, json);
      assertTrue(bundle.getEntry().size() > 1);
      assertTrue(bundle.getEntryFirstRep().getResource() instanceof Patient);
    }
  }

  @Test
  public void testCompressedNdjsonExport() throws Exception {
    Config.set("exporter.fhir.compress_data", "true");
    Config.set("exporter.fhir.bulk_data", "true");
    int people = 2;
    List<File> files = generateAndList(people);

    assertEquals(people, files.size());
    IParser parser = FhirR4.getContext().newJsonParser();
    for (File file : files) {
      assertTrue(file.getName(), file.getName().endsWith(".ndjson.gz"));
      List<String> lines = readGzLines(file);
      assertTrue(lines.size() > 1);
      List<Resource> resources = new ArrayList<>();
      for (String line : lines) {
        resources.add((Resource) parser.parseResource(line));
      }
      assertTrue(resources.get(0) instanceof Patient);
      long patients = resources.stream().filter(r -> r instanceof Patient).count();
      assertEquals(1, patients);
    }
  }

  @Test
  public void testCompressedNdjsonExportWritesNoManifest() throws Exception {
    Config.set("exporter.fhir.compress_data", "true");
    Config.set("exporter.fhir.bulk_data", "true");
    File stale = new File(new File(outputFolder, "fhir"), "parameters.json");
    stale.getParentFile().mkdirs();
    Files.write(stale.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
    Generator generator = new Generator(1);
    generator.options.overflow = false;
    generator.generatePerson(0);

    Exporter.runPostCompletionExports(generator);

    assertFalse("mixed-type files must not be listed in a manifest", stale.exists());
    File[] files = new File(outputFolder, "fhir").listFiles();
    assertEquals(1, files.length);
    assertTrue(files[0].getName(), files[0].getName().endsWith(".ndjson.gz"));
  }

  @Test
  public void testUncompressedNdjsonExportWritesManifest() throws Exception {
    Config.set("exporter.fhir.bulk_data", "true");
    Generator generator = new Generator(1);
    generator.options.overflow = false;
    generator.generatePerson(0);

    Exporter.runPostCompletionExports(generator);

    String manifest = new String(Files.readAllBytes(
        new File(new File(outputFolder, "fhir"), "parameters.json").toPath()),
        StandardCharsets.UTF_8);
    assertTrue(manifest, manifest.contains("Patient.ndjson"));
  }

  @Test
  public void testCompressedExportDoesNotOverwrite() throws Exception {
    File existing = tempFolder.newFile("existing.ndjson.gz");
    Files.write(existing.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
    Bundle bundle = new Bundle();
    bundle.addEntry().setResource(new Patient());

    Exporter.writeNewGzNdjsonFile(existing.toPath(), bundle,
        FhirR4.getContext().newJsonParser());

    assertEquals("keep", new String(Files.readAllBytes(existing.toPath()),
        StandardCharsets.UTF_8));
    assertTrue(!new File(existing.getPath() + ".part").exists());
  }
}
