package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.domain.service.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * This file is UTF-8 and holds characters that look alike but are not: a precomposed e-acute next
 * to an "e" plus a combining accent, an invisible bidi override, a surrogate pair. Each is named in
 * a comment by its code point, because an editor that silently normalizes the file would otherwise
 * turn a passing test into one that no longer asserts anything.
 */
@DisplayName("Normalizer")
class NormalizerTest {

  /** U+1F600 GRINNING FACE: one code point, two UTF-16 chars. */
  private static final String EMOJI = "😀";

  /** "Systematique" with a precomposed e-acute. */
  private static final String COMPOSED = "Systématique";

  /** The same word spelled as "e" plus U+0301 COMBINING ACUTE ACCENT. */
  private static final String DECOMPOSED = "Systématique";

  @Nested
  @DisplayName("display name")
  class DisplayNameOf {

    @Test
    void trimsSurroundingWhitespace() {
      assertThat(Normalizer.displayName("   Acme Cloud   ")).isEqualTo("Acme Cloud");
    }

    @Test
    void collapsesInternalSpaceRunsToOneSpace() {
      assertThat(Normalizer.displayName("Acme    Cloud     Services"))
          .isEqualTo("Acme Cloud Services");
    }

    @Test
    void collapsesASpaceRunThatFollowsAStrippedCharacter() {
      assertThat(Normalizer.displayName("Acme\n  Cloud")).isEqualTo("Acme Cloud");
    }

    /**
     * Regression guard. Tab, newline and carriage return are all {@code \p{Cc}}, so stripping
     * control characters before collapsing whitespace deleted the separator and ran two words
     * together - a name pasted from a spreadsheet arrived as "AcmeCloud". That also gave it a
     * different comparison key from the same name typed by hand, which would have created a second
     * organization instead of reusing the existing one.
     */
    @Test
    void treatsTabsAndNewlinesAsWordSeparatorsRatherThanDeletingThem() {
      assertThat(Normalizer.displayName("Acme\tCloud")).isEqualTo("Acme Cloud");
      assertThat(Normalizer.displayName("Acme\tCloud\t\tServices")).isEqualTo("Acme Cloud Services");
      assertThat(Normalizer.displayName("Acme\r\nCloud")).isEqualTo("Acme Cloud");
    }

    @Test
    void givesAPastedNameTheSameComparisonKeyAsATypedOne() {
      assertThat(Normalizer.comparisonKey(Normalizer.displayName("Acme\tCloud")))
          .isEqualTo(Normalizer.comparisonKey(Normalizer.displayName("Acme Cloud")));
    }

    @Test
    void composesDecomposedCharactersSoTwoSpellingsOfOneNameAgree() {
      assertThat(DECOMPOSED).isNotEqualTo(COMPOSED);
      assertThat(Normalizer.displayName(DECOMPOSED)).isEqualTo(Normalizer.displayName(COMPOSED));
      assertThat(Normalizer.displayName(DECOMPOSED)).isEqualTo(COMPOSED);
    }

    @Test
    void stripsControlCharacters() {
      assertThat(Normalizer.displayName("Acme Cloud")).isEqualTo("Acme Cloud");
    }

    @Test
    void stripsBidirectionalFormatCharactersThatCouldReorderTheStage() {
      // U+202E RIGHT-TO-LEFT OVERRIDE would otherwise reverse the text rendered after it on the
      // presentation screen, letting one submission rewrite how its neighbours read.
      assertThat(Normalizer.displayName("‮Acme‎ Cloud")).isEqualTo("Acme Cloud");
    }

    @Test
    void keepsAccentedLettersIntact() {
      assertThat(Normalizer.displayName("Šumava Řešení"))
          .isEqualTo("Šumava Řešení");
    }

    @Test
    void rejectsNullInput() {
      assertThatThrownBy(() -> Normalizer.displayName(null))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }

    @Test
    void rejectsNameShorterThanTwoCharacters() {
      assertThatThrownBy(() -> Normalizer.displayName("a"))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "", "‮‎", "a"})
    void rejectsInputThatIsTooShortOnceCleaned(String raw) {
      assertThatThrownBy(() -> Normalizer.displayName(raw))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }

    @Test
    void acceptsNameOfExactlyTwoCharacters() {
      assertThat(Normalizer.displayName("ab")).isEqualTo("ab");
    }

    @Test
    void acceptsNameOfExactlySixtyCharacters() {
      assertThat(Normalizer.displayName("a".repeat(60))).hasSize(60);
    }

    @Test
    void rejectsNameOfSixtyOneCharacters() {
      assertThatThrownBy(() -> Normalizer.displayName("a".repeat(61)))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }

    @Test
    void countsLengthAfterTrimmingSoPaddingCannotPushANameOverTheLimit() {
      assertThat(Normalizer.displayName("   " + "a".repeat(60) + "   ")).hasSize(60);
    }

    @Test
    void rejectsASingleAstralCharacterBecauseASurrogatePairCountsAsOneCharacter() {
      assertThat(EMOJI).hasSize(2); // two UTF-16 chars...
      assertThat(EMOJI.codePointCount(0, EMOJI.length())).isEqualTo(1); // ...but one character

      assertThatThrownBy(() -> Normalizer.displayName(EMOJI))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }

    @Test
    void acceptsTwoAstralCharacters() {
      assertThat(Normalizer.displayName(EMOJI.repeat(2))).isEqualTo(EMOJI.repeat(2));
    }

    @Test
    void acceptsSixtyAstralCharactersEvenThoughTheStringHolds120Chars() {
      String sixtyCodePoints = EMOJI.repeat(60);
      assertThat(sixtyCodePoints).hasSize(120);
      assertThat(Normalizer.displayName(sixtyCodePoints)).isEqualTo(sixtyCodePoints);
    }

    @Test
    void rejectsSixtyOneAstralCharacters() {
      assertThatThrownBy(() -> Normalizer.displayName(EMOJI.repeat(61)))
          .isInstanceOfSatisfying(DomainException.class, NormalizerTest::assertNameValidation);
    }
  }

  @Nested
  @DisplayName("comparison key")
  class ComparisonKeyOf {

    @Test
    void ignoresCase() {
      assertThat(Normalizer.comparisonKey("ACME Cloud"))
          .isEqualTo(Normalizer.comparisonKey("acme cloud"));
    }

    @Test
    void ignoresSurroundingAndRepeatedWhitespace() {
      assertThat(Normalizer.comparisonKey("  Acme   Cloud "))
          .isEqualTo(Normalizer.comparisonKey("Acme Cloud"));
    }

    @Test
    void foldsFullWidthFormsToTheirAsciiEquivalent() {
      // U+FF21/U+FF22 FULLWIDTH LATIN CAPITAL A/B: a phone keyboard can produce these, and they
      // name the same company as the ASCII spelling.
      assertThat(Normalizer.comparisonKey("ＡＢ")).isEqualTo("ab");
    }

    @Test
    void foldsLigaturesToTheirCompatibilityEquivalent() {
      // U+FB01 LATIN SMALL LIGATURE FI
      assertThat(Normalizer.comparisonKey("ﬁnance"))
          .isEqualTo(Normalizer.comparisonKey("finance"));
    }

    @Test
    void preservesDiacriticsSoAccentedAndUnaccentedNamesStayDistinct() {
      assertThat(Normalizer.comparisonKey("Système"))
          .isNotEqualTo(Normalizer.comparisonKey("Systeme"));
    }

    @Test
    void agreesForComposedAndDecomposedSpellingsOfOneName() {
      assertThat(Normalizer.comparisonKey(DECOMPOSED)).isEqualTo(Normalizer.comparisonKey(COMPOSED));
    }

    @Test
    void stripsControlAndFormatCharacters() {
      assertThat(Normalizer.comparisonKey("Acme‮ Cloud")).isEqualTo("acme cloud");
    }

    @Test
    void isStableForTheDisplayNameItWasDerivedFrom() {
      String display = Normalizer.displayName("  ACME   Cloud ");
      assertThat(Normalizer.comparisonKey(display)).isEqualTo("acme cloud");
    }
  }

  private static void assertNameValidation(DomainException exception) {
    assertThat(exception.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
    assertThat(exception.field()).isEqualTo("target.name");
  }
}
