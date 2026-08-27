package eu.sovereigntylens.unit;

import eu.sovereigntylens.config.AppProperties;
import java.time.Duration;
import java.util.List;

/**
 * Hand-built {@link AppProperties} for tests that need one without a Spring context.
 *
 * <p>Bean validation is not applied here, which is what lets a test construct deliberately
 * degenerate configuration - a negative session lifetime, for instance - to reach a branch that
 * would otherwise need a clock to advance.
 */
final class AppPropertiesFixture {

  static final String AUTH_SECRET = "unit-test-auth-secret-that-is-long-enough";
  static final String HASH_SECRET = "unit-test-contributor-secret-long-enough";

  private AppPropertiesFixture() {}

  static AppProperties defaults() {
    return with(AUTH_SECRET, HASH_SECRET, Duration.ofHours(8), 150);
  }

  static AppProperties withAuthSecret(String authSecret) {
    return with(authSecret, HASH_SECRET, Duration.ofHours(8), 150);
  }

  static AppProperties withContributorHashSecret(String hashSecret) {
    return with(AUTH_SECRET, hashSecret, Duration.ofHours(8), 150);
  }

  static AppProperties withAdminSessionTtl(Duration ttl) {
    return with(AUTH_SECRET, HASH_SECRET, ttl, 150);
  }

  static AppProperties withRoundCapacity(int roundCapacity) {
    return with(AUTH_SECRET, HASH_SECRET, Duration.ofHours(8), roundCapacity);
  }

  static AppProperties with(
      String authSecret, String hashSecret, Duration adminSessionTtl, int roundCapacity) {
    return new AppProperties(
        "unit-test-password",
        authSecret,
        hashSecret,
        "http://localhost:3000",
        List.of("http://localhost:3000"),
        roundCapacity,
        adminSessionTtl,
        Duration.ofSeconds(15),
        Duration.ofHours(2));
  }
}
