package org.mitre.synthea.helpers;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.mitre.synthea.world.agents.Person;

/**
 * Generates unique, checksum-valid NHS numbers.
 *
 * <p>An NHS number is ten digits: a nine digit root followed by a Modulus 11 check digit.
 * Roots whose check digit would be 10 are invalid and are skipped.</p>
 *
 * <p>Two allocation modes are supported:</p>
 * <ul>
 *   <li>Random (default): the first digit is {@code generate.patient.nhs_number_prefix}
 *   (default 9, which is never issued to real patients in England) and the remaining eight root
 *   digits are random.</li>
 *   <li>Sequential: if {@code generate.patient.nhs_number.range.start} and
 *   {@code generate.patient.nhs_number.range.end} are set, roots are allocated in order from
 *   that inclusive range and an {@link IllegalStateException} is thrown when it is exhausted.
 *   Setting only one bound, or bounds outside 0 &lt;= start &lt;= end &lt;= 999999999, is an
 *   error.</li>
 * </ul>
 *
 * <p>Every number handed out (or {@link #reserve reserved}) by an instance is unique for the
 * lifetime of that instance, so a simulation run uses one instance for everyone.</p>
 */
public class NhsNumberGenerator implements NationalIdGenerator {
  public static final String NHS_NUMBER_SYSTEM = "https://fhir.nhs.uk/Id/nhs-number";
  private static final long MAX_ROOT = 999_999_999L;
  private static final String RANGE_START_KEY = "generate.patient.nhs_number.range.start";
  private static final String RANGE_END_KEY = "generate.patient.nhs_number.range.end";
  private static final long UNSET = -1L;
  /** Mixed into a person's seed for their NHS number's own random stream. */
  private static final long NHS_NUMBER_SEED_SALT = 0x4E48534E554D4245L;

  private final Set<String> generatedNhsNumbers =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
  private final long rangeEnd;
  private final AtomicLong rangeCounter;
  private final int prefix;

  /**
   * Create a new generator, reading the prefix and optional range from the Config.
   */
  public NhsNumberGenerator() {
    long rangeStart = Config.getAsLong(RANGE_START_KEY, UNSET);
    long rangeEnd = Config.getAsLong(RANGE_END_KEY, UNSET);
    if (rangeStart == UNSET && rangeEnd == UNSET) {
      this.rangeEnd = UNSET;
      this.rangeCounter = null;
    } else {
      if (rangeStart == UNSET || rangeEnd == UNSET) {
        throw new IllegalArgumentException("Set both " + RANGE_START_KEY + " and "
            + RANGE_END_KEY + " to allocate NHS numbers from a range, or neither");
      }
      if (rangeStart < 0 || rangeEnd > MAX_ROOT || rangeStart > rangeEnd) {
        throw new IllegalArgumentException("Invalid NHS number range " + rangeStart + "-"
            + rangeEnd + ": need 0 <= " + RANGE_START_KEY + " <= " + RANGE_END_KEY
            + " <= " + MAX_ROOT);
      }
      this.rangeEnd = rangeEnd;
      this.rangeCounter = new AtomicLong(rangeStart);
    }
    this.prefix = Config.getAsInteger("generate.patient.nhs_number_prefix", 9);
    if (prefix < 0 || prefix > 9) {
      throw new IllegalArgumentException("NHS number prefix must be a single digit");
    }
  }

  @Override
  public String attribute() {
    return Person.IDENTIFIER_NHS_NUMBER;
  }

  @Override
  public String system() {
    return NHS_NUMBER_SYSTEM;
  }

  /**
   * Issue a new NHS number for a person. The random digits come from a stream seeded from the
   * person's seed, so that issuing the number does not change the rest of their record.
   * @param person the person.
   * @return a ten digit NHS number.
   */
  @Override
  public String generate(Person person) {
    return randomNhsNumber(
        new DefaultRandomNumberGenerator(person.getSeed() ^ NHS_NUMBER_SEED_SALT));
  }

  @Override
  public void reserve(String nhsNumber) {
    generatedNhsNumbers.add(nhsNumber);
  }

  private static int[] getDigits(long root) {
    if (root < 0 || root > MAX_ROOT) {
      throw new IllegalArgumentException("NHS root out of range: " + root);
    }
    int[] d = new int[9];
    for (int i = 8; i >= 0; i--) {
      d[i] = (int) (root % 10);
      root /= 10;
    }
    return d;
  }

  /**
   * Compute the Modulus 11 check digit for a nine digit NHS number root.
   * @param digits the nine root digits.
   * @return -1 if the root is invalid (check digit would be 10), otherwise the check digit.
   */
  static int computeCheckDigit(int[] digits) {
    int sum = 0;
    for (int i = 0; i < 9; i++) {
      sum += digits[i] * (10 - i);
    }
    int checkDigit = 11 - (sum % 11);
    if (checkDigit == 11) {
      checkDigit = 0;
    }
    if (checkDigit == 10) {
      return -1;
    }
    return checkDigit;
  }

  /**
   * Check whether a string is a valid NHS number: exactly ten digits with a correct Modulus 11
   * check digit.
   * @param nhsNumber the candidate.
   * @return true if valid.
   */
  public static boolean isValid(String nhsNumber) {
    if (nhsNumber == null || !nhsNumber.matches("[0-9]{10}")) {
      return false;
    }
    int[] digits = new int[9];
    for (int i = 0; i < 9; i++) {
      digits[i] = Character.digit(nhsNumber.charAt(i), 10);
    }
    int check = computeCheckDigit(digits);
    return check >= 0 && check == Character.digit(nhsNumber.charAt(9), 10);
  }

  private static String buildNumber(int[] digits, int checkDigit) {
    StringBuilder sb = new StringBuilder(10);
    for (int d : digits) {
      sb.append(d);
    }
    sb.append(checkDigit);
    return sb.toString();
  }

  /**
   * Keep asking the supplier for root digits until a valid, unused NHS number is produced.
   */
  private String generateUnique(Supplier<int[]> digitsSupplier) {
    while (true) {
      int[] digits = digitsSupplier.get();
      int checkDigit = computeCheckDigit(digits);
      if (checkDigit < 0) {
        continue;
      }
      String nhs = buildNumber(digits, checkDigit);
      if (generatedNhsNumbers.add(nhs)) {
        return nhs;
      }
    }
  }

  private String nextSequentialNhsNumber() {
    return generateUnique(() -> {
      long candidate = rangeCounter.getAndIncrement();
      if (candidate > rangeEnd) {
        throw new IllegalStateException("Configured NHS number range exhausted");
      }
      return getDigits(candidate);
    });
  }

  /**
   * Generate a new unique NHS number.
   * @param random source of randomness (ignored when a sequential range is configured).
   * @return a ten digit NHS number.
   */
  public String randomNhsNumber(RandomNumberGenerator random) {
    if (rangeCounter != null) {
      return nextSequentialNhsNumber();
    }
    return generateUnique(() -> {
      int[] digits = new int[9];
      digits[0] = prefix;
      for (int i = 1; i < 9; i++) {
        digits[i] = random.randInt(10);
      }
      return digits;
    });
  }
}
