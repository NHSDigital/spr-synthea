package org.mitre.synthea.identity;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Map;

import org.junit.Test;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;

public class VariantTest {

  private Seed loadFirstSeed() throws IOException {
    String rawJSON = Utilities.readResource("identity/test_records.json");
    EntityManager em = EntityManager.fromJSON(rawJSON);
    Entity e = em.getRecords().get(0);
    return e.getSeeds().get(0);
  }

  @Test
  public void demographicAttributesForPerson() throws IOException {
    Seed firstSeed = loadFirstSeed();
    Variant firstVariant = firstSeed.getVariants().get(0);
    Map<String, Object> attrs = firstVariant.demographicAttributesForPerson();
    assertEquals("Margarita Ebony", attrs.get(Person.FIRST_NAME));
  }

  @Test
  public void nhsNumberFallsBackToSeed() throws IOException {
    Seed firstSeed = loadFirstSeed();
    assertEquals("9434765919", firstSeed.getNhsNumber());
    Variant firstVariant = firstSeed.getVariants().get(0);
    assertEquals("9434765919", firstVariant.getNhsNumber());
    assertEquals("9434765919",
        firstVariant.demographicAttributesForPerson().get(Person.IDENTIFIER_NHS_NUMBER));
  }

  @Test
  public void variantCanOverrideNhsNumber() throws IOException {
    Variant secondVariant = loadFirstSeed().getVariants().get(1);
    assertEquals("4010232137", secondVariant.getNhsNumber());
    assertEquals("4010232137",
        secondVariant.demographicAttributesForPerson().get(Person.IDENTIFIER_NHS_NUMBER));
  }
}
