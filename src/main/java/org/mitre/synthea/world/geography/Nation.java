package org.mitre.synthea.world.geography;

import java.util.Locale;

import org.mitre.synthea.helpers.Config;

/**
 * The nation the simulation is set in, selected by {@code generate.geography.country_code}.
 *
 * <p>The four UK nations use the UK government country codes (ISO 3166-2, see
 * https://www.gov.uk/government/publications/open-standards-for-government/country-codes) because
 * they differ in identifiers (NHS numbers in England and Wales, CHI numbers in Scotland, H&amp;C
 * numbers in Northern Ireland), demographics, ethnicity categories and clinical guidance.
 * Each implemented UK nation keeps its data in {@code geography/<folder>/}, so a nation is added
 * by dropping in that folder; see {@link Resource}.</p>
 *
 * <p>Any other code (e.g. US) keeps upstream Synthea's behaviour and file locations.</p>
 */
public enum Nation {
  ENGLAND("GB-ENG", "england", "South West", null),
  WALES("GB-WLS", null, null,
      "needs Welsh demographics, providers, names and NHS Wales ethnicity categories"),
  SCOTLAND("GB-SCT", null, null,
      "needs Scottish demographics, providers and CHI numbers instead of NHS numbers"),
  NORTHERN_IRELAND("GB-NIR", null, null,
      "needs Northern Ireland demographics, providers and Health and Care (H&C) numbers"),
  /** Upstream Synthea behaviour: US data, or files configured explicitly for another country. */
  UPSTREAM(null, null, "Massachusetts", null);

  public static final String COUNTRY_CODE_KEY = "generate.geography.country_code";
  public static final String DEFAULT_COUNTRY_CODE = "GB-ENG";

  /**
   * The nation and resource paths for one configuration version; see {@link #resolved()}.
   */
  private static final class Resolved {
    final long configVersion;
    final Nation nation;
    final String[] paths = new String[Resource.values().length];

    Resolved(long configVersion) {
      this.configVersion = configVersion;
      this.nation = fromCountryCode(configuredCode());
      for (Resource resource : Resource.values()) {
        paths[resource.ordinal()] = resource.path(nation);
      }
    }
  }

  private static volatile Resolved resolved;

  /**
   * The nation and resource paths for the current configuration. They are called for every
   * person, so they are cached and recomputed only when the configuration changes (for example
   * when a test switches nation with Config.set).
   */
  private static Resolved resolved() {
    long version = Config.version();
    Resolved current = resolved;
    if (current == null || current.configVersion != version) {
      // read the version first: a change while resolving makes the next call resolve again
      current = new Resolved(version);
      resolved = current;
    }
    return current;
  }

  private final String code;
  private final String folder;
  private final String defaultState;
  private final String notImplementedReason;

  Nation(String code, String folder, String defaultState, String notImplementedReason) {
    this.code = code;
    this.folder = folder;
    this.defaultState = defaultState;
    this.notImplementedReason = notImplementedReason;
  }

  /**
   * The nation for the configured {@code generate.geography.country_code}.
   * @return the configured nation.
   * @throws IllegalStateException if the code names a nation that is not implemented yet.
   */
  public static Nation current() {
    return resolved().nation;
  }

  /**
   * Resolve a country code to a nation.
   * @param countryCode e.g. "GB-ENG" or "US".
   * @return the nation; UPSTREAM for any code that is not a UK code.
   * @throws IllegalStateException for "GB"/"UK" (ambiguous) and UK nations not implemented yet.
   */
  public static Nation fromCountryCode(String countryCode) {
    String normalised = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
    if (normalised.equals("GB-CYM")) {
      normalised = WALES.code;
    }
    if (normalised.equals("GB") || normalised.equals("UK")) {
      throw new IllegalStateException(COUNTRY_CODE_KEY + " = " + countryCode
          + " is ambiguous: data differs between the UK nations. Use GB-ENG for England.");
    }
    for (Nation nation : values()) {
      if (normalised.equals(nation.code)) {
        if (nation.notImplementedReason != null) {
          throw new IllegalStateException(COUNTRY_CODE_KEY + " = " + nation.code + " ("
              + nation.name() + ") is not supported yet: it " + nation.notImplementedReason
              + ". Only GB-ENG (England) is implemented.");
        }
        return nation;
      }
    }
    return UPSTREAM;
  }

  /** The configured country code, e.g. "GB-ENG" or "US". */
  public static String configuredCode() {
    return Config.get(COUNTRY_CODE_KEY, DEFAULT_COUNTRY_CODE);
  }

  /**
   * The ISO 3166-1 alpha-2 country, e.g. "GB" for any UK nation, for addresses and birthplaces.
   * @return the ISO country code.
   */
  public static String isoCountryCode() {
    return current() == UPSTREAM ? configuredCode() : "GB";
  }

  /** The default region/state when none is given: the nation default unless configured. */
  public static String defaultState() {
    return Config.get("generate.geography.default_state", current().defaultState);
  }

  /**
   * The FHIR identifier system for passports issued by this country, unless configured.
   * @return e.g. http://hl7.org/fhir/sid/passport-GBR for the UK nations.
   */
  public static String passportUri() {
    return Config.get("generate.geography.passport_uri",
        current() == UPSTREAM ? "http://hl7.org/fhir/sid/passport-USA"
            : "http://hl7.org/fhir/sid/passport-GBR");
  }

  /** Whether patients in this nation are given NHS numbers. */
  public boolean usesNhsNumbers() {
    return this == ENGLAND;
  }

  /** The resource folder for this nation, e.g. "geography/england", or null for UPSTREAM. */
  public String resourceFolder() {
    return folder == null ? null : "geography/" + folder;
  }

  /**
   * Nation-specific resources. A resource is found, in order:
   * <ol>
   *   <li>from its configuration key, if set (an empty value means "none");</li>
   *   <li>for a UK nation, in {@code geography/<nation>/}, if that file exists;</li>
   *   <li>for UPSTREAM, at upstream Synthea's default location.</li>
   * </ol>
   */
  public enum Resource {
    DEMOGRAPHICS("generate.demographics.default_file", "geography/demographics.csv",
        "demographics.csv"),
    POSTCODES("generate.geography.zipcodes.default_file", "geography/zipcodes.csv",
        "postcodes.csv"),
    SDOH("generate.geography.sdoh.default_file", "geography/sdoh.csv", "sdoh.csv"),
    TIMEZONES("generate.geography.timezones.default_file", "geography/timezones.csv",
        "timezones.csv"),
    NAMES("generate.names.default_file", "names.yml", "names.yml"),
    /** Ethnic groups, national ethnic categories and main languages (UK nations only). */
    ETHNICITY("generate.demographics.ethnicity_file", null, "ethnicity.json"),
    HOSPITALS("generate.providers.hospitals.default_file", "providers/hospitals.csv",
        "providers/hospitals.csv"),
    IHS_HOSPITALS("generate.providers.ihs.hospitals.default_file",
        "providers/ihs_facilities.csv", null),
    VETERANS("generate.providers.veterans.default_file", "providers/va_facilities.csv", null),
    PRIMARY_CARE("generate.providers.primarycare.default_file",
        "providers/primary_care_facilities.csv", "providers/primary_care_facilities.csv"),
    IHS_PRIMARY_CARE("generate.providers.ihs.primarycare.default_file",
        "providers/ihs_centers.csv", null),
    URGENT_CARE("generate.providers.urgentcare.default_file",
        "providers/urgent_care_facilities.csv", "providers/urgent_care_facilities.csv"),
    HOME_HEALTH("generate.providers.homehealth.default_file",
        "providers/home_health_agencies.csv", "providers/home_health_agencies.csv"),
    HOSPICE("generate.providers.hospice.default_file", "providers/hospice.csv",
        "providers/hospice.csv"),
    NURSING("generate.providers.nursing.default_file", "providers/nursing.csv",
        "providers/nursing.csv"),
    PAYERS("generate.payers.insurance_companies.default_file",
        "payers/insurance_companies.csv", "payers/insurance_companies.csv"),
    PAYER_PLANS("generate.payers.insurance_plans.default_file", "payers/insurance_plans.csv",
        "payers/insurance_plans.csv"),
    PAYER_ELIGIBILITIES("generate.payers.insurance_plans.eligibilities_file",
        "payers/insurance_eligibilities.csv", "payers/insurance_eligibilities.csv");

    public final String configKey;
    private final String upstreamPath;
    private final String nationFile;

    Resource(String configKey, String upstreamPath, String nationFile) {
      this.configKey = configKey;
      this.upstreamPath = upstreamPath;
      this.nationFile = nationFile;
    }

    /**
     * The path of this resource for the configured nation.
     * @return the path, or null if the nation has no such resource.
     */
    public String path() {
      return resolved().paths[ordinal()];
    }

    /**
     * The path of this resource for the given nation.
     * @param nation the nation.
     * @return the path, or null if the nation has no such resource.
     */
    public String path(Nation nation) {
      String configured = Config.get(configKey);
      if (configured != null) {
        return configured.trim().isEmpty() ? null : configured.trim();
      }
      if (nation.folder == null) {
        return upstreamPath;
      }
      if (nationFile == null) {
        return null;
      }
      String candidate = nation.resourceFolder() + "/" + nationFile;
      return Nation.class.getClassLoader().getResource(candidate) == null ? null : candidate;
    }
  }
}
