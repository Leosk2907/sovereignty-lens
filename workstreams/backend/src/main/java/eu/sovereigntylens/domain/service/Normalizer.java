package eu.sovereigntylens.domain.service;

import eu.sovereigntylens.domain.DomainException;
import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Company-name normalization.
 *
 * <p>Two values come out of one raw submission: the display name that is stored and rendered, and
 * the comparison key that decides whether two audience members named the same company. Keeping both
 * here, in framework-free domain code, means the deduplication rule is unit-testable and the
 * database function stays simple.
 */
public final class Normalizer {

  /** Minimum accepted company name length, counted in Unicode code points. */
  public static final int MIN_LENGTH = 2;

  /** Maximum accepted company name length, counted in Unicode code points. */
  public static final int MAX_LENGTH = 60;

  private static final Pattern WHITESPACE_RUN =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private Normalizer() {}

  /**
   * Produces the stored, rendered form of a submitted name: canonical composition, no control or
   * formatting characters, internal whitespace runs collapsed to one space, trimmed.
   *
   * <p>Stripping format characters also removes bidirectional overrides, which would otherwise let
   * a submission reorder the text around it on the presentation screen.
   *
   * @throws DomainException with {@code VALIDATION_ERROR} when the result is not 2-60 code points
   */
  public static String displayName(String raw) {
    if (raw == null) {
      throw DomainException.validation("Company name is required.", "target.name");
    }
    String cleaned = collapse(java.text.Normalizer.normalize(raw, Form.NFC));
    int length = cleaned.codePointCount(0, cleaned.length());
    if (length < MIN_LENGTH || length > MAX_LENGTH) {
      throw DomainException.validation(
          "Company name must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.",
          "target.name");
    }
    return cleaned;
  }

  /**
   * Produces the case-insensitive, compatibility-folded key used for deduplication within a
   * session. Diacritics are deliberately preserved: "Système" and "Systeme" are different companies.
   */
  public static String comparisonKey(String displayName) {
    return collapse(java.text.Normalizer.normalize(displayName, Form.NFKC)).toLowerCase(Locale.ROOT);
  }

  private static String collapse(String value) {
    // Order matters. Tab, newline and carriage return are all category Cc, so
    // stripping control characters first would delete the separator and run two
    // words together - "Acme<TAB>Cloud" became "AcmeCloud", which also made a
    // pasted name a different organization from the same name typed by hand.
    // Whitespace collapses to a single space first; only then are the remaining
    // control and format characters removed, and a final pass tidies the gaps
    // that removal can leave behind.
    String separated = WHITESPACE_RUN.matcher(value).replaceAll(" ");
    String withoutControls = CONTROL_CHARACTERS.matcher(separated).replaceAll("");
    return WHITESPACE_RUN.matcher(withoutControls).replaceAll(" ").trim();
  }
}
