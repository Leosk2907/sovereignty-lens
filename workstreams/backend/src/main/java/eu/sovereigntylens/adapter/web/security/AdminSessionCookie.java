package eu.sovereigntylens.adapter.web.security;

import eu.sovereigntylens.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the presenter's session cookie.
 *
 * <p>The token is {@code v1.<base64url(payload)>.<base64url(hmac)>} where the payload is {@code
 * issuedAtEpochSeconds:expiresAtEpochSeconds}. There is no server-side session store and no
 * identity beyond "someone knew the shared password", so the token needs to carry nothing else. The
 * signature covers the payload string exactly as it is transmitted, which is what makes verifying
 * it a matter of recomputing one MAC over bytes the client cannot influence the framing of.
 *
 * <p>Everything that can go wrong - absent, malformed, wrong version, wrong signature, expired -
 * returns the same {@code false}. The caller turns that into one generic 401, so a probe cannot
 * learn which of its guesses was closer.
 *
 * <p><b>Deliberate trade-off:</b> {@code Secure} is set only when the request itself arrived over
 * HTTPS. The demo is presented from a laptop on a conference LAN, reached over plain HTTP by IP
 * address, where a {@code Secure} cookie would simply never be stored and the presenter could not
 * log in at all. Behind TLS the attribute appears automatically. {@code HttpOnly} and {@code
 * SameSite=Lax} are unconditional, so script access and cross-site submission are closed either
 * way; what remains open on a plain-HTTP LAN is a passive observer on that LAN, which is the risk
 * accepted for the length of one talk.
 */
@Component
public class AdminSessionCookie {

  /** Name of the presenter session cookie. */
  public static final String NAME = "sl_admin";

  private static final String VERSION = "v1";
  private static final String SEPARATOR = ".";
  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /**
   * Tolerated forward clock skew when a token claims to have been issued in the future. Presenter
   * and server can be different machines; a minute of drift must not lock the presenter out.
   */
  private static final Duration ISSUE_SKEW = Duration.ofMinutes(1);

  private final byte[] secret;
  private final Duration ttl;

  public AdminSessionCookie(AppProperties properties) {
    this.secret = properties.authSecret().getBytes(StandardCharsets.UTF_8);
    this.ttl = properties.adminSessionTtl();
  }

  /** Mints a token valid for the configured session lifetime. */
  public String issue() {
    Instant now = Instant.now();
    String payload = now.getEpochSecond() + ":" + now.plus(ttl).getEpochSecond();
    return VERSION
        + SEPARATOR
        + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + SEPARATOR
        + ENCODER.encodeToString(sign(payload));
  }

  /**
   * True only for a token this service signed and that has not expired.
   *
   * <p>The payload is parsed after the signature is verified, never before: an unverified token is
   * attacker-controlled input, and nothing it claims - least of all its own expiry - may influence
   * a decision until the MAC says it is ours.
   */
  public boolean isValid(String token) {
    if (token == null) {
      return false;
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || !VERSION.equals(parts[0])) {
      return false;
    }

    byte[] payloadBytes;
    byte[] signature;
    try {
      payloadBytes = DECODER.decode(parts[1]);
      signature = DECODER.decode(parts[2]);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }

    String payload = new String(payloadBytes, StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(sign(payload), signature)) {
      return false;
    }
    return isUnexpired(payload);
  }

  /**
   * The {@code Set-Cookie} value that establishes the session.
   *
   * @param secure whether the request arrived over HTTPS; see the class documentation for why this
   *     is not unconditionally true
   */
  public ResponseCookie toCookie(String token, boolean secure) {
    return ResponseCookie.from(NAME, token)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/")
        .maxAge(ttl)
        .build();
  }

  /**
   * The {@code Set-Cookie} value that clears the session.
   *
   * <p>Sent without {@code Secure} on purpose: browsers match a replacement cookie by name, domain
   * and path only, so this clears the session on both schemes, and a logout that silently failed to
   * remove the cookie would be worse than one sent without the attribute.
   */
  public ResponseCookie expiredCookie() {
    return ResponseCookie.from(NAME, "")
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build();
  }

  private boolean isUnexpired(String payload) {
    int separator = payload.indexOf(':');
    if (separator < 0) {
      return false;
    }
    long issuedAt;
    long expiresAt;
    try {
      issuedAt = Long.parseLong(payload.substring(0, separator));
      expiresAt = Long.parseLong(payload.substring(separator + 1));
    } catch (NumberFormatException malformed) {
      return false;
    }
    long now = Instant.now().getEpochSecond();
    return now < expiresAt && issuedAt <= now + ISSUE_SKEW.toSeconds();
  }

  /** A {@link Mac} is not thread safe, so each signature gets its own instance. */
  private byte[] sign(String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is mandatory in every Java SE implementation, and the key is validated at
      // startup, so reaching here means the runtime is not one this service can run on.
      throw new IllegalStateException("Cannot sign the admin session cookie", e);
    }
  }
}
