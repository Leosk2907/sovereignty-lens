package eu.sovereigntylens.application;

import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Turns the anonymous browser identifier into the opaque {@code contributorHash} that enforces "one
 * contribution per browser per round".
 *
 * <p>Keying the digest with a deployment secret is what makes the hash irreversible in practice: the
 * identifier is a UUID, so a plain digest of it could be brute-forced back to the original and would
 * additionally be stable across deployments, letting one database correlate contributors with
 * another.
 *
 * <p>The raw identifier is never logged and never stored - only the hash leaves this class.
 */
@Component
public class ContributorHasher {

  private static final String ALGORITHM = "HmacSHA256";
  private static final HexFormat HEX = HexFormat.of();

  // SecretKeySpec is immutable and safe to share; Mac is not, and so is created per call below.
  // A ThreadLocal would be the usual way to amortise that, but this service runs on virtual
  // threads: each request already gets a fresh thread, so a cache keyed by thread would allocate
  // just as often while keeping key state alive on threads that are about to be discarded.
  private final SecretKeySpec key;

  public ContributorHasher(AppProperties properties) {
    this.key =
        new SecretKeySpec(
            properties.contributorHashSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  /**
   * Hashes a client-supplied anonymous identifier into its lowercase hex HMAC-SHA256 digest.
   *
   * @param anonymousClientId the raw browser identifier, which must be a UUID
   * @throws DomainException with {@code VALIDATION_ERROR} when the identifier is not a UUID
   */
  public String hash(String anonymousClientId) {
    return HEX.formatHex(newMac().doFinal(canonicalIdentifier(anonymousClientId)));
  }

  /**
   * Requiring a UUID keeps the identifier space uniform: a caller that could pick arbitrary text
   * could also pick a value colliding with another browser's quota, or vary its own to get a second
   * contribution.
   */
  private static byte[] canonicalIdentifier(String anonymousClientId) {
    if (anonymousClientId == null || anonymousClientId.isBlank()) {
      throw DomainException.validation("A client identifier is required.", "anonymousClientId");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(anonymousClientId);
    } catch (IllegalArgumentException e) {
      // The offending value is deliberately absent from the message: it must not reach a log.
      throw DomainException.validation(
          "The client identifier must be a UUID.", "anonymousClientId");
    }
    // Round-tripping rejects the loose spellings UUID.fromString accepts, such as short groups,
    // which would otherwise let one browser present two identifiers that parse to the same value.
    if (!parsed.toString().equalsIgnoreCase(anonymousClientId)) {
      throw DomainException.validation("The client identifier must be a UUID.", "anonymousClientId");
    }
    // Hashing the canonical lowercase rendering, so changing the case of an identifier cannot buy a
    // second slot in the round.
    return parsed.toString().getBytes(StandardCharsets.UTF_8);
  }

  private Mac newMac() {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is mandatory in every JRE, so this can only mean a broken runtime.
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }
}
