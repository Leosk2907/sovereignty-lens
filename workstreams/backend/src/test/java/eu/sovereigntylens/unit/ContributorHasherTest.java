package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.sovereigntylens.application.ContributorHasher;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ContributorHasher")
class ContributorHasherTest {

  private static final String CLIENT_A = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
  private static final String CLIENT_B = "3f2504e0-4f89-41d3-9a0c-0305e82c3302";

  private final ContributorHasher hasher = new ContributorHasher(AppPropertiesFixture.defaults());

  @Test
  void producesTheSameHashForTheSameIdentifierAndSecret() {
    assertThat(hasher.hash(CLIENT_A)).isEqualTo(hasher.hash(CLIENT_A));
  }

  @Test
  void producesTheSameHashAcrossTwoInstancesSharingASecret() {
    // The quota is enforced across restarts, so the digest cannot depend on instance state.
    ContributorHasher restarted = new ContributorHasher(AppPropertiesFixture.defaults());

    assertThat(restarted.hash(CLIENT_A)).isEqualTo(hasher.hash(CLIENT_A));
  }

  @Test
  void producesDifferentHashesForDifferentIdentifiers() {
    assertThat(hasher.hash(CLIENT_A)).isNotEqualTo(hasher.hash(CLIENT_B));
  }

  @Test
  void producesADifferentHashForTheSameIdentifierUnderADifferentSecret() {
    // Proves the secret genuinely keys the digest: without it, two deployments could correlate
    // their contributors by comparing stored hashes.
    ContributorHasher otherDeployment =
        new ContributorHasher(
            AppPropertiesFixture.withContributorHashSecret(
                "a-different-contributor-secret-of-length-32"));

    assertThat(otherDeployment.hash(CLIENT_A)).isNotEqualTo(hasher.hash(CLIENT_A));
  }

  @Test
  void producesLowercaseHexOfTheFullSha256Width() {
    assertThat(hasher.hash(CLIENT_A)).matches("[0-9a-f]{64}");
  }

  @Test
  void neverLeavesTheRawIdentifierInsideTheHash() {
    String hash = hasher.hash(CLIENT_A);

    assertThat(hash).doesNotContain(CLIENT_A);
    assertThat(hash).doesNotContain(CLIENT_A.replace("-", ""));
    // Nor any long recognisable run of it.
    assertThat(hash).doesNotContain("3f2504e0");
  }

  @Test
  void canonicalisesCaseSoOneBrowserCannotBuyASecondSlotByShoutingItsIdentifier() {
    assertThat(hasher.hash(CLIENT_A.toUpperCase(java.util.Locale.ROOT)))
        .isEqualTo(hasher.hash(CLIENT_A));
  }

  @Test
  void spreadsIdentifiersThatDifferByOneCharacterAcrossTheOutputSpace() {
    String a = hasher.hash(CLIENT_A);
    String b = hasher.hash(CLIENT_B);

    assertThat(a).hasSameSizeAs(b);
    assertThat(differingCharacters(a, b)).isGreaterThan(a.length() / 4);
  }

  @Test
  void hashesEveryDistinctIdentifierToADistinctValue() {
    // Fixed seed: a collision reported by this test must be reproducible.
    var random = new java.util.Random(20260301L);
    var hashes = new java.util.HashSet<String>();
    for (int i = 0; i < 200; i++) {
      hashes.add(hasher.hash(new UUID(random.nextLong(), random.nextLong()).toString()));
    }

    assertThat(hashes).hasSize(200);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "not-a-uuid",
        "3f2504e0-4f89-41d3-9a0c",
        "3f2504e0-4f89-41d3-9a0c-0305e82c3301-extra",
        "{3f2504e0-4f89-41d3-9a0c-0305e82c3301}",
      })
  void rejectsAnIdentifierThatIsNotAUuid(String identifier) {
    assertThatThrownBy(() -> hasher.hash(identifier))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
              assertThat(exception.field()).isEqualTo("anonymousClientId");
            });
  }

  @Test
  void rejectsTheLooseSpellingsUuidFromStringWouldOtherwiseAccept() {
    // "1-1-1-1-1" parses, but it lets one browser present two identifiers that resolve to one
    // value and so pass the per-round quota twice.
    assertThatThrownBy(() -> hasher.hash("1-1-1-1-1"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void neverNamesTheOffendingIdentifierInTheFailureMessage() {
    // The message reaches a log; the identifier must not.
    assertThatThrownBy(() -> hasher.hash("secret-looking-value"))
        .isInstanceOf(DomainException.class)
        .hasMessageNotContaining("secret-looking-value");
  }

  private static int differingCharacters(String left, String right) {
    int differences = 0;
    for (int i = 0; i < left.length(); i++) {
      if (left.charAt(i) != right.charAt(i)) {
        differences++;
      }
    }
    return differences;
  }
}
