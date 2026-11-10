package org.mitre.synthea.world.concepts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.world.agents.Person;

/**
 * Checks name origin selection with the upstream (US) names file and validation of
 * name_origins in custom names files.
 */
public class NameOriginsTest {

  private static final String KEY = "generate.names.default_file";
  private final String original = Config.get(KEY);

  /** Restore the configured names file. */
  @After
  public void restore() {
    if (original == null) {
      Config.remove(KEY);
    } else {
      Config.set(KEY, original);
    }
    Names.reloadNames();
  }

  @Test
  public void upstreamNamesKeepLanguageBehaviourWithoutRandomDraws() {
    Names.reloadNames();
    Person a = new Person(42L);
    Person b = new Person(42L);
    assertEquals("spanish", Names.nameOrigin("spanish", null, a));
    assertEquals("english", Names.nameOrigin("english", null, a));
    assertEquals("english", Names.nameOrigin("polish", "C", a));
    assertEquals("no random draws for single-origin choices", b.rand(), a.rand(), 0.0);
  }

  @Test
  public void filesWithoutNameOriginsUseListsNamedAfterLanguages() throws Exception {
    useNames("english: {M: [Tom], F: [Ann], family: [Smith]}\n"
        + "welsh: {M: [Dafydd], F: [Cerys], family: [Jones]}\n"
        + "street: {type: [Road]}\n");
    Person person = new Person(1L);
    assertEquals("welsh", Names.nameOrigin("welsh", null, person));
    assertEquals("english", Names.nameOrigin("french", null, person));
    String first = Names.fakeFirstName("F", "welsh", person);
    assertTrue(first, first.startsWith("Cerys"));
  }

  @Test
  public void weightedDefaultMixesOrigins() throws Exception {
    useNames("name_origins: {default: {english: 75, welsh: 25}}\n"
        + "english: {M: [Tom], F: [Ann], family: [Smith]}\n"
        + "welsh: {M: [Dafydd], F: [Cerys], family: [Jones]}\n"
        + "street: {type: [Road]}\n");
    Person person = new Person(9L);
    int welsh = 0;
    int n = 4000;
    for (int i = 0; i < n; i++) {
      if ("welsh".equals(Names.nameOrigin(null, null, person))) {
        welsh++;
      }
    }
    double share = welsh / (double) n;
    assertTrue("welsh share " + share, share > 0.2 && share < 0.3);
  }

  @Test
  public void originWithoutListsIsRejected() throws Exception {
    try {
      useNames("name_origins: {by_language: {welsh: welsh}, default: {english: 1}}\n"
          + "english: {M: [Tom], F: [Ann], family: [Smith]}\n");
      fail("expected an error for the missing welsh lists");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("'welsh'"));
    }
  }

  private static void useNames(String yaml) throws Exception {
    File file = File.createTempFile("names", ".yml");
    file.deleteOnExit();
    Files.writeString(file.toPath(), yaml);
    Config.set("generate.names.default_file", file.getAbsolutePath());
    Names.reloadNames();
  }
}
