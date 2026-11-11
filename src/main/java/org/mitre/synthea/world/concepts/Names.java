package org.mitre.synthea.world.concepts;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.RandomCollection;
import org.mitre.synthea.helpers.SimpleYML;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.geography.Nation;

/**
 * Provides utilities for generating and managing names.
 */
public class Names {

  private static SimpleYML names = loadNames();
  private static NameOrigins origins = new NameOrigins(names);

  /**
   * Reload the names file for the configured nation (or generate.names.default_file).
   * Intended for use after the configuration has been changed, e.g. in tests.
   */
  public static void reloadNames() {
    names = loadNames();
    origins = new NameOrigins(names);
  }

  private static SimpleYML loadNames() {
    String filename = Nation.Resource.NAMES.path();
    try {
      String namesData = Utilities.readResource(filename, false, true);
      return new SimpleYML(namesData);
    } catch (Exception e) {
      System.err.println("ERROR: unable to load yml: " + filename);
      e.printStackTrace();
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * The name_origins section of a names file: which name lists (origins) people's names come
   * from, chosen by main language, then by national ethnic category, then a default mix.
   */
  private static class NameOrigins {
    private final Map<String, String> byLanguage = new HashMap<>();
    private final Map<String, RandomCollection<String>> byEthnicCategory = new HashMap<>();
    private final RandomCollection<String> defaults;
    private final String fallback;
    /** Every origin named in the rules, so each can be checked for name lists. */
    private final Set<String> referenced = new LinkedHashSet<>();

    @SuppressWarnings("unchecked")
    NameOrigins(SimpleYML names) {
      Map<String, Object> section = (Map<String, Object>) names.get("name_origins");
      Map<String, Number> defaultWeights;
      if (section == null) {
        // Files without a name_origins section: a language selects its own list, if present
        for (String key : names.topLevelKeys()) {
          if (hasLists(names, key)) {
            byLanguage.put(key, key);
          }
        }
        defaultWeights = Map.of("english", 1);
      } else {
        Map<String, String> languages = (Map<String, String>) section.get("by_language");
        if (languages != null) {
          byLanguage.putAll(languages);
        }
        Map<String, Map<String, Number>> categories =
            (Map<String, Map<String, Number>>) section.get("by_ethnic_category");
        if (categories != null) {
          for (Map.Entry<String, Map<String, Number>> category : categories.entrySet()) {
            byEthnicCategory.put(category.getKey(), weightedOrigins(category.getValue()));
          }
        }
        defaultWeights = (Map<String, Number>) section.get("default");
      }
      if (defaultWeights == null || defaultWeights.isEmpty()) {
        throw new IllegalStateException("name_origins.default must list at least one origin");
      }
      fallback = defaultWeights.keySet().iterator().next();
      defaults = weightedOrigins(defaultWeights);
      referenced.addAll(byLanguage.values());
      for (String origin : referenced) {
        if (!hasLists(names, origin)) {
          throw new IllegalStateException("Names file " + Nation.Resource.NAMES.path()
              + " refers to name origin '" + origin + "' without M, F and family lists");
        }
      }
    }

    private RandomCollection<String> weightedOrigins(Map<String, Number> weights) {
      RandomCollection<String> origins = new RandomCollection<>();
      for (Map.Entry<String, Number> origin : weights.entrySet()) {
        origins.add(origin.getValue().doubleValue(), origin.getKey());
        referenced.add(origin.getKey());
      }
      return origins;
    }

    private static boolean hasLists(SimpleYML names, String origin) {
      Object lists = names.get(origin);
      // Checkstyle 8.4 cannot parse pattern-matching instanceof, so cast explicitly
      if (!(lists instanceof Map)) {
        return false;
      }
      Map<?, ?> map = (Map<?, ?>) lists;
      return map.get("M") instanceof List
          && map.get("F") instanceof List
          && map.get("family") instanceof List;
    }

    String pick(String language, String ethnicCategory, Person person) {
      String origin = byLanguage.get(language);
      if (origin != null) {
        return origin;
      }
      RandomCollection<String> weighted =
          byEthnicCategory.getOrDefault(ethnicCategory, defaults);
      // a single origin draws no random number, so the US random stream is unchanged
      return weighted.nextDrawingOnlyIfNeeded(person);
    }
  }

  /**
   * Choose the origin of a person's names (which name lists to use): by main language if the
   * names file maps it, else by the weights for their national ethnic category, else the
   * file's default mix. See name_origins in the names file.
   * @param language the person's main language, e.g. "polish"; may be null.
   * @param ethnicCategory the person's national ethnic category, e.g. "H"; may be null.
   * @param person the person, for random draws.
   * @return the name origin, e.g. "english" or "indian".
   */
  public static String nameOrigin(String language, String ethnicCategory, Person person) {
    return origins.pick(language, ethnicCategory, person);
  }

  /**
   * The name origin for a person, from their first language and national ethnic category.
   * @param person the person.
   * @return the name origin.
   */
  public static String nameOrigin(Person person) {
    return nameOrigin((String) person.attributes.get(Person.FIRST_LANGUAGE),
        (String) person.attributes.get(Person.ETHNIC_CATEGORY), person);
  }

  @SuppressWarnings("unchecked")
  private static List<String> namesFor(String origin, String list) {
    String key = NameOrigins.hasLists(names, origin) ? origin : origins.fallback;
    return (List<String>) ((Map<String, Object>) names.get(key)).get(list);
  }

  /**
   * Indicates whether numbers should be appended to generated names.
   * These numbers are used to indicate the patients as not real people.
   */
  public static final boolean appendNumbersToNames =
      Config.getAsBoolean("generate.append_numbers_to_person_names", false);

  /**
   * Generate a first name appropriate for a given gender and name origin.
   * If `generate.append_numbers_to_person_names` == true,
   * then numbers will be appended automatically.
   * @param gender Gender of the name, "M" or "F"
   * @param origin Origin of the name, e.g. "english", "spanish" (see {@link #nameOrigin});
   *     an origin without lists uses the names file's default origin.
   * @param person person to generate a name for.
   * @return First name.
   */
  public static String fakeFirstName(String gender, String origin, Person person) {
    List<String> choices = namesFor(origin, gender);

    // pick a random item from the list
    String name = choices.get(person.randInt(choices.size()));

    if (appendNumbersToNames) {
      name = addHash(name);
    }

    return name;
  }

  /**
   * Generate a surname appropriate for a given name origin.
   * If `generate.append_numbers_to_person_names` == true,
   * then numbers will be appended automatically.
   * @param origin Origin of the name, e.g. "english", "spanish" (see {@link #nameOrigin});
   *     an origin without lists uses the names file's default origin.
   * @param person person to generate a name for.
   * @return Surname or Family Name.
   */
  public static String fakeLastName(String origin, Person person) {
    List<String> choices = namesFor(origin, "family");
    // pick a random item from the list
    String name = choices.get(person.randInt(choices.size()));

    if (appendNumbersToNames) {
      name = addHash(name);
    }

    return name;
  }

  /**
   * Generate a Street Address.
   * @param includeLine2 Whether or not the address should have a second line,
   *     which can take the form of an apartment, unit, or suite number.
   * @param person person to generate an address for.
   * @return First name.
   */
  @SuppressWarnings("unchecked")
  public static String fakeAddress(boolean includeLine2, Person person) {
    int number = person.randInt(1000) + 100;
    List<String> n = namesFor(origins.fallback, "family");
    // for now just use family names as the street name.
    // could expand with a few more but probably not worth it
    String streetName = n.get(person.randInt(n.size()));
    List<String> a = (List<String>)names.get("street.type");
    String streetType = a.get(person.randInt(a.size()));

    if (includeLine2) {
      int addtlNum = person.randInt(100);
      List<String> s = (List<String>)names.get("street.secondary");
      String addtlType = s.get(person.randInt(s.size()));
      return number + " " + streetName + " " + streetType + " " + addtlType + " " + addtlNum;
    } else {
      return number + " " + streetName + " " + streetType;
    }
  }

  /**
   * Adds a 1- to 3-digit hashcode to the end of the name.
   * @param name Person's name
   * @return The name with a hash appended, ex "John123" or "Smith22"
   */
  public static String addHash(String name) {
    // note that this value should be deterministic
    // It cannot be a random number. It needs to be a hash value or something deterministic.
    // We do not want John10 and John52 -- we want all the Johns to have the SAME numbers. e.g. All
    // people named John become John52
    // Why? Because we do not know how using systems will index names. Say a user of an system
    // loaded with Synthea data wants to find all the people named John Smith. This will be easier
    // if John Smith always resolves to John52 Smith32 and not [John52 Smith32, John10 Smith22, ...]
    return name + Integer.toString(Math.abs(name.hashCode() % 1000));
  }
}
