package eu.sovereigntylens.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration.
 *
 * <p>The three secrets have no default anywhere in the codebase. A deployment that forgets one
 * fails to start rather than silently running with a guessable value.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    /*
     * Shared presenter password for /api/admin/login. The API is published on
     * every interface so phones can reach it, which puts this endpoint on the
     * venue network; a short password would be trivially guessable there.
     */
    @NotBlank @Size(min = 12) String adminPassword,

    /* Key for signing the admin session cookie. */
    @NotBlank @Size(min = 32) String authSecret,

    /* Key for hashing anonymous browser identifiers before storage. */
    @NotBlank @Size(min = 32) String contributorHashSecret,

    /* Public origin of the audience-facing frontend, used to build QR codes. */
    @NotBlank String publicBaseUrl,

    /* Origins allowed to call this API from a browser. */
    @NotEmpty List<String> corsAllowedOrigins,

    /* Maximum active audience dependencies per round. */
    @Min(1) int roundCapacity,

    /* Lifetime of an admin session cookie. */
    Duration adminSessionTtl,

    /* Interval between Server-Sent Events heartbeat comments. */
    Duration sseHeartbeatInterval,

    /* How long a Server-Sent Events connection is held open before the client reconnects. */
    Duration sseConnectionTimeout) {

  public AppProperties {
    adminSessionTtl = adminSessionTtl == null ? Duration.ofHours(8) : adminSessionTtl;
    sseHeartbeatInterval =
        sseHeartbeatInterval == null ? Duration.ofSeconds(15) : sseHeartbeatInterval;
    sseConnectionTimeout = sseConnectionTimeout == null ? Duration.ofHours(2) : sseConnectionTimeout;
    corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
  }

  /** Absolute URL a phone opens after scanning the contribution QR code. */
  public String contributeUrl() {
    return trimmedBaseUrl() + "/contribute";
  }

  /** Absolute URL of the presentation view. */
  public String presentUrl() {
    return trimmedBaseUrl() + "/present";
  }

  private String trimmedBaseUrl() {
    return publicBaseUrl.endsWith("/")
        ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
        : publicBaseUrl;
  }
}
