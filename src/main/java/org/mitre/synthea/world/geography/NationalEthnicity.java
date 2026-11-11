package org.mitre.synthea.world.geography;

import static org.mitre.synthea.world.geography.Nation.Resource.ETHNICITY;

import com.google.gson.reflect.TypeToken;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mitre.synthea.helpers.RandomCollection;
import org.mitre.synthea.helpers.RandomNumberGenerator;
import org.mitre.synthea.helpers.Utilities;

/**
 * A nation's ethnicity model, loaded from its {@code ethnicity.json} (see
 * {@link Nation.Resource#ETHNICITY}): the ethnic group columns of its demographics file, the
 * national ethnic category recorded within each group, and main language by group.
 * Nations without this file (e.g. the US) use upstream Synthea's race and Hispanic ethnicity.
 */
public class NationalEthnicity implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final Map<String, NationalEthnicity> CACHE = new ConcurrentHashMap<>();

  private final List<String> groups;
  private final String categorySystem;
  private final String whiteWithOtherMainLanguage;
  private final String defaultCategory;
  private final Map<String, String> displays;
  private final Map<String, RandomCollection<String>> categories = new LinkedHashMap<>();
  private final Map<String, RandomCollection<String>> languages = new LinkedHashMap<>();

  private NationalEthnicity(Map<String, Object> raw) {
    this.groups = Collections.unmodifiableList(castList(raw.get("groups")));
    Map<String, Object> category = castMap(raw.get("ethnic_category"));
    this.categorySystem = (String) category.get("system");
    this.whiteWithOtherMainLanguage = (String) category.get("white_with_other_main_language");
    this.defaultCategory = (String) category.get("default");
    this.displays = Collections.unmodifiableMap(castMap(category.get("displays")));
    toCollections(castMap(category.get("weights")), categories);
    toCollections(castMap(raw.get("main_language")), languages);
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> castList(Object o) {
    return (List<T>) o;
  }

  @SuppressWarnings("unchecked")
  private static <T> Map<String, T> castMap(Object o) {
    return (Map<String, T>) o;
  }

  private static void toCollections(Map<String, Object> weightsByGroup,
      Map<String, RandomCollection<String>> out) {
    for (Map.Entry<String, Object> group : weightsByGroup.entrySet()) {
      RandomCollection<String> collection = new RandomCollection<>();
      Map<String, Object> weights = castMap(group.getValue());
      for (Map.Entry<String, Object> weight : weights.entrySet()) {
        collection.add(((Number) weight.getValue()).doubleValue(), weight.getKey());
      }
      out.put(group.getKey(), collection);
    }
  }

  /**
   * The ethnicity model of a nation.
   * @param nation the nation.
   * @return the model, or null if the nation uses upstream race/ethnicity.
   */
  public static NationalEthnicity of(Nation nation) {
    String path = nation.getResource(ETHNICITY);
    return path == null ? null : load(path);
  }

  /**
   * Load (and cache) an ethnicity model.
   * @param path resource path of an ethnicity.json file.
   * @return the model.
   */
  public static NationalEthnicity load(String path) {
    return CACHE.computeIfAbsent(path, p -> {
      try {
        Map<String, Object> raw = Utilities.getGson().fromJson(
            Utilities.readResource(p, true, true),
            new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
        return new NationalEthnicity(raw);
      } catch (Exception e) {
        throw new IllegalStateException("Unable to load ethnicity model " + p, e);
      }
    });
  }

  /** Ethnic group columns in the nation's demographics.csv, e.g. WHITE, MIXED. */
  public List<String> getGroups() {
    return groups;
  }

  /** Code system of the national ethnic category, e.g. UKCore-EthnicCategoryEngland. */
  public String getCategorySystem() {
    return categorySystem;
  }

  /**
   * Display text for an ethnic category code.
   * @param code e.g. "A".
   * @return the display, or null if unknown.
   */
  public String getCategoryDisplay(String code) {
    return displays.get(code);
  }

  /**
   * Pick a main language for someone in the given (lower case) ethnic group.
   * @param group e.g. "white".
   * @param random the random number generator to use.
   * @return the language, "english" for an unknown group.
   */
  public String pickMainLanguage(String group, RandomNumberGenerator random) {
    RandomCollection<String> usage = languages.get(group);
    return usage == null ? "english" : usage.next(random);
  }

  /**
   * Pick a national ethnic category within an ethnic group.
   * @param group e.g. "white".
   * @param language the person's main language.
   * @param random the random number generator to use.
   * @return the ethnic category code, e.g. "A".
   */
  public String pickCategory(String group, String language, RandomNumberGenerator random) {
    if (whiteWithOtherMainLanguage != null && "white".equals(group)
        && !"english".equals(language)) {
      return whiteWithOtherMainLanguage;
    }
    RandomCollection<String> weights = categories.get(group);
    return weights == null ? defaultCategory : weights.next(random);
  }
}
