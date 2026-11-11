package org.mitre.synthea.world.geography;

import java.util.Locale;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.NationalIdGenerator;
import org.mitre.synthea.helpers.NhsNumberGenerator;
import org.mitre.synthea.helpers.SocialSecurityNumberGenerator;

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
 * <p>US, and any other non-UK code, uses Synthea's default US behaviour and file locations.</p>
 *
 * <p>There is no global "current nation": the Generator resolves the configured nation once per
 * run and stores it in its {@link Location}. People, providers and clinicians reach it through
 * their location ({@code Person.getNation()}, {@code Provider.getNation()},
 * {@code Clinician.getNation()}), so static helpers that take a person need no other state.</p>
 */
public enum Nation {
  ENGLAND("GB-ENG", "england", "South West", null),
  WALES("GB-WLS", null, null,
      "needs Welsh demographics, providers, names and NHS Wales ethnicity categories"),
  SCOTLAND("GB-SCT", null, null,
      "needs Scottish demographics, providers and CHI numbers instead of NHS numbers"),
  NORTHERN_IRELAND("GB-NIR", null, null,
      "needs Northern Ireland demographics, providers and Health and Care (H&C) numbers"),
  /** The United States: Synthea's default US data, or files configured for another country. */
  USA(null, null, "Massachusetts", null);

  public static final String COUNTRY_CODE_KEY = "generate.geography.country_code";
  public static final String DEFAULT_COUNTRY_CODE = "GB-ENG";

  private final String code;
  private final String folder;
  private final String defaultState;
  private final String notImplementedReason;
  /**
   * This nation's resource paths, cached until the configuration changes; see
   * {@link #getResource(Resource)}.
   */
  private volatile ResolvedPaths resolvedPaths;

  Nation(String code, String folder, String defaultState, String notImplementedReason) {
    this.code = code;
    this.folder = folder;
    this.defaultState = defaultState;
    this.notImplementedReason = notImplementedReason;
  }

  /**
   * The nation named by {@code generate.geography.country_code}. The Generator resolves this
   * once per run and stores it in its {@link Location}; everything else should take the nation
   * from a Location (or a Person's, see {@link #of(Location)}).
   * @return the configured nation.
   * @throws IllegalStateException if the code names a nation that is not implemented yet.
   */
  public static Nation configured() {
    return fromCountryCode(Config.get(COUNTRY_CODE_KEY, DEFAULT_COUNTRY_CODE));
  }

  /**
   * The nation of a location.
   * @param location the location, or null for a person or provider created without one (as in
   *     some unit tests).
   * @return the location's nation, or the {@link #configured()} nation if there is no location.
   */
  public static Nation of(Location location) {
    return location == null ? configured() : location.getNation();
  }

  /**
   * Resolve a country code to a nation.
   * @param countryCode e.g. "GB-ENG" or "US".
   * @return the nation; USA for any code that is not a UK code.
   * @throws IllegalStateException for "GB"/"UK" (ambiguous) and UK nations not implemented yet.
   */
  public static Nation fromCountryCode(String countryCode) {
    String normalised = canonicalCode(countryCode);
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
    return USA;
  }

  /** Trim and upper-case a country code, mapping the Welsh-language GB-CYM to GB-WLS. */
  private static String canonicalCode(String countryCode) {
    String canonical = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
    return canonical.equals("GB-CYM") ? WALES.code : canonical;
  }

  /**
   * The country code of this nation, stored in each person's {@code Person.NATION}.
   * @return e.g. "GB-ENG"; for USA, the configured non-UK code (e.g. "US"), or "US" when
   *     the configuration names a UK nation.
   */
  public String code() {
    if (code != null) {
      return code;
    }
    String configured = canonicalCode(Config.get(COUNTRY_CODE_KEY, "US"));
    return configured.isEmpty() || configured.startsWith("GB") || configured.equals("UK")
        ? "US" : configured;
  }

  /**
   * The ISO 3166-1 alpha-2 country, e.g. "GB" for any UK nation, for addresses and birthplaces.
   * @return the ISO country code.
   */
  public String isoCountryCode() {
    return this == USA ? code() : "GB";
  }

  /** The default region/state when none is given: this nation's default unless configured. */
  public String defaultState() {
    return Config.get("generate.geography.default_state", defaultState);
  }

  /**
   * The FHIR identifier system for passports issued by this country, unless configured.
   * @return e.g. http://hl7.org/fhir/sid/passport-GBR for the UK nations.
   */
  public String passportUri() {
    return Config.get("generate.geography.passport_uri",
        this == USA ? "http://hl7.org/fhir/sid/passport-USA"
            : "http://hl7.org/fhir/sid/passport-GBR");
  }

  /**
   * A new generator of this nation's personal identifiers (NHS numbers in England, Social
   * Security Numbers in the USA). Create one per simulation run so identifiers are unique in it.
   * @return the generator.
   */
  public NationalIdGenerator newNationalIdGenerator() {
    switch (this) {
      case ENGLAND:
        return new NhsNumberGenerator();
      case USA:
        return new SocialSecurityNumberGenerator();
      default:
        throw new IllegalStateException(this + " is not implemented yet: " + notImplementedReason);
    }
  }

  /**
   * The path of one of this nation's resources, e.g. {@code nation.getResource(ETHNICITY)}.
   * @param resource the resource.
   * @return the classpath or file path, or null if this nation has no such resource.
   */
  public String getResource(Resource resource) {
    return paths().paths[resource.ordinal()];
  }

  /** The resource folder for this nation, e.g. "geography/england", or null for USA. */
  public String resourceFolder() {
    return folder == null ? null : "geography/" + folder;
  }

  /**
   * Nation-specific resources. A resource is found, in order:
   * <ol>
   *   <li>from its configuration key, if set (an empty value means "none");</li>
   *   <li>for a UK nation, in {@code geography/<nation>/}, if that file exists;</li>
   *   <li>for USA, at Synthea's default (US) location.</li>
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
    IMMUNIZATION_SCHEDULE("generate.immunization_schedule", "immunization_schedule.json",
        "immunization_schedule.json"),
    /** Ethnic groups, national ethnic categories and main languages (UK nations only). */
    ETHNICITY("generate.demographics.ethnicity_file", null, "ethnicity.json"),
    /** LOINC vital sign to SNOMED CT observable mappings for UK Core (UK nations only). */
    VITAL_SIGN_SNOMED("exporter.fhir.vital_sign_snomed_file", null, "vital_sign_snomed.json"),
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

    private String resolve(Nation nation) {
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

  /** The resource paths of one nation for one configuration version. */
  private static final class ResolvedPaths {
    final long configVersion;
    final String[] paths = new String[Resource.values().length];

    ResolvedPaths(Nation nation, long configVersion) {
      this.configVersion = configVersion;
      for (Resource resource : Resource.values()) {
        paths[resource.ordinal()] = resource.resolve(nation);
      }
    }
  }

  /**
   * This nation's resource paths. They are looked up for every person, so they are cached and
   * recomputed only when the configuration changes (for example when a test sets a file).
   */
  private ResolvedPaths paths() {
    long version = Config.version();
    ResolvedPaths current = resolvedPaths;
    if (current == null || current.configVersion != version) {
      // read the version first: a change while resolving makes the next call resolve again
      current = new ResolvedPaths(this, version);
      resolvedPaths = current;
    }
    return current;
  }
}
