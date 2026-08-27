package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.adapter.web.security.AdminSessionCookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseCookie;

@DisplayName("AdminSessionCookie")
class AdminSessionCookieTest {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final AdminSessionCookie cookies =
      new AdminSessionCookie(AppPropertiesFixture.defaults());

  @Nested
  @DisplayName("token verification")
  class Verification {

    @Test
    void acceptsAFreshlyIssuedToken() {
      assertThat(cookies.isValid(cookies.issue())).isTrue();
    }

    @Test
    void issuesTheDocumentedThreePartVersionedFormat() {
      String[] parts = cookies.issue().split("\\.", -1);

      assertThat(parts).hasSize(3);
      assertThat(parts[0]).isEqualTo("v1");
      assertThat(new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8))
          .matches("\\d+:\\d+");
    }

    @Test
    void rejectsATamperedPayload() {
      String[] parts = cookies.issue().split("\\.", -1);
      String payload = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);
      // Push the claimed expiry a century out; the signature still covers the original payload.
      String forged = payload.substring(0, payload.indexOf(':') + 1) + "4102444800";
      String tampered =
          parts[0]
              + "."
              + ENCODER.encodeToString(forged.getBytes(StandardCharsets.UTF_8))
              + "."
              + parts[2];

      assertThat(cookies.isValid(tampered)).isFalse();
    }

    @Test
    void rejectsATamperedSignature() {
      String[] parts = cookies.issue().split("\\.", -1);
      byte[] signature = DECODER.decode(parts[2]);
      signature[0] ^= 0x01;
      String tampered = parts[0] + "." + parts[1] + "." + ENCODER.encodeToString(signature);

      assertThat(cookies.isValid(tampered)).isFalse();
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
      AdminSessionCookie otherDeployment =
          new AdminSessionCookie(
              AppPropertiesFixture.withAuthSecret("a-completely-different-secret-of-length-32"));

      assertThat(cookies.isValid(otherDeployment.issue())).isFalse();
      assertThat(otherDeployment.isValid(cookies.issue())).isFalse();
    }

    @Test
    void rejectsATokenWhoseExpiryHasPassed() {
      // A negative lifetime mints a token that was already expired when it was signed, which
      // exercises the expiry branch without the test having to wait for a clock.
      AdminSessionCookie alreadyExpired =
          new AdminSessionCookie(
              AppPropertiesFixture.withAdminSessionTtl(Duration.ofMinutes(-5)));
      String token = alreadyExpired.issue();

      assertThat(alreadyExpired.isValid(token)).isFalse();
    }

    @Test
    void acceptsATokenStillWithinItsLifetime() {
      AdminSessionCookie shortLived =
          new AdminSessionCookie(AppPropertiesFixture.withAdminSessionTtl(Duration.ofMinutes(5)));

      assertThat(shortLived.isValid(shortLived.issue())).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
          "   ",
          "not-a-token",
          "v1",
          "v1.",
          "v1.onlytwo",
          "v1.a.b.c",
          "v2.aGVsbG8.aGVsbG8", // right shape, wrong version
          "v1.!!!not-base64!!!.aGVsbG8",
          "v1.aGVsbG8.!!!not-base64!!!",
        })
    void rejectsAMalformedToken(String token) {
      assertThat(cookies.isValid(token)).isFalse();
    }

    @Test
    void rejectsAWellSignedTokenWhosePayloadIsNotATimePair() {
      // Signed by this service, so the MAC passes; the payload still has to parse.
      AdminSessionCookie sameSecret = new AdminSessionCookie(AppPropertiesFixture.defaults());
      String[] parts = sameSecret.issue().split("\\.", -1);
      String nonsense = ENCODER.encodeToString("not-a-time-pair".getBytes(StandardCharsets.UTF_8));

      assertThat(cookies.isValid(parts[0] + "." + nonsense + "." + parts[2])).isFalse();
    }
  }

  @Nested
  @DisplayName("cookie attributes")
  class Attributes {

    @Test
    void isNamedForThePresenterSession() {
      assertThat(AdminSessionCookie.NAME).isEqualTo("sl_admin");
      assertThat(cookies.toCookie("token", false).getName()).isEqualTo("sl_admin");
    }

    @Test
    void closesScriptAccessAndCrossSiteSubmission() {
      ResponseCookie cookie = cookies.toCookie("token", false);

      assertThat(cookie.isHttpOnly()).isTrue();
      assertThat(cookie.getSameSite()).isEqualTo("Lax");
      assertThat(cookie.getPath()).isEqualTo("/");
    }

    @Test
    void marksTheCookieSecureOnlyWhenTheRequestArrivedOverHttps() {
      assertThat(cookies.toCookie("token", true).isSecure()).isTrue();
      assertThat(cookies.toCookie("token", false).isSecure()).isFalse();
    }

    @Test
    void carriesTheConfiguredSessionLifetime() {
      AdminSessionCookie shortLived =
          new AdminSessionCookie(AppPropertiesFixture.withAdminSessionTtl(Duration.ofMinutes(30)));

      assertThat(shortLived.toCookie("token", false).getMaxAge())
          .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void clearsTheSessionWithAZeroMaxAgeCookie() {
      ResponseCookie expired = cookies.expiredCookie();

      assertThat(expired.getMaxAge()).isEqualTo(Duration.ZERO);
      assertThat(expired.getValue()).isEmpty();
      assertThat(expired.getName()).isEqualTo(AdminSessionCookie.NAME);
    }

    @Test
    void clearsOnBothSchemesByOmittingSecureFromTheLogoutCookie() {
      // Browsers match a replacement cookie by name, domain and path only, so a logout cookie
      // without Secure clears the session whether or not the original was set over HTTPS.
      ResponseCookie expired = cookies.expiredCookie();

      assertThat(expired.isSecure()).isFalse();
      assertThat(expired.isHttpOnly()).isTrue();
      assertThat(expired.getSameSite()).isEqualTo("Lax");
      assertThat(expired.getPath()).isEqualTo("/");
    }
  }
}
