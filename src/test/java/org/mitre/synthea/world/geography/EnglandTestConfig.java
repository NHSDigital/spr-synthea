package org.mitre.synthea.world.geography;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mitre.synthea.helpers.Config;

/**
 * Switches the (US) test configuration to England, GB-ENG, as configured in synthea.properties:
 * every nation resource then comes from geography/england/ rather than the explicit US files in
 * synthea-overrides.properties. Call {@link #restore()} afterwards.
 */
public final class EnglandTestConfig {
  private static final Map<String, String> saved = new HashMap<>();

  private EnglandTestConfig() {
  }

  /** Configure GB-ENG and remove all per-file overrides, remembering the current values. */
  public static synchronized void apply() {
    List<String> keys = new ArrayList<>();
    for (Nation.Resource resource : Nation.Resource.values()) {
      keys.add(resource.configKey);
    }
    keys.add("generate.geography.default_state");
    keys.add("generate.geography.passport_uri");
    keys.add(Nation.COUNTRY_CODE_KEY);
    for (String key : keys) {
      saved.putIfAbsent(key, Config.get(key));
      Config.remove(key);
    }
    Config.set(Nation.COUNTRY_CODE_KEY, "GB-ENG");
  }

  /**
   * Restore the configuration saved by {@link #apply()}.
   */
  public static synchronized void restore() {
    saved.forEach((key, value) -> {
      if (value == null) {
        Config.remove(key);
      } else {
        Config.set(key, value);
      }
    });
    saved.clear();
  }
}
