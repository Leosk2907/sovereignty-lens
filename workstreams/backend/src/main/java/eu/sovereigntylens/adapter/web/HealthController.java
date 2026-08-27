package eu.sovereigntylens.adapter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe.
 *
 * <p>Deliberately answers from memory with no database round-trip. The container healthcheck polls
 * this while the service is still starting and while Flyway migrates, and a probe that waited on
 * the database would report the service dead exactly when it is busy coming up correctly. Readiness
 * of the database is a separate concern, covered by the actuator health endpoint.
 */
@RestController
@Tag(name = "Health", description = "Liveness probe used by the container healthcheck")
public class HealthController {

  /**
   * Fallback for a build that did not generate {@code build-info.properties}. Kept in step with the
   * pom version by hand, which is acceptable because the field is informational.
   */
  private static final String UNKNOWN_BUILD_VERSION = "0.1.0";

  private final String version;

  public HealthController(ObjectProvider<BuildProperties> buildProperties) {
    BuildProperties build = buildProperties.getIfAvailable();
    this.version = build == null ? UNKNOWN_BUILD_VERSION : build.getVersion();
  }

  @Operation(
      summary = "Service health",
      description = "Answers without touching the database, so it is usable during startup.")
  @ApiResponse(responseCode = "200", description = "The service is up")
  @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<HealthStatus> health() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new HealthStatus("ok", version, Instant.now()));
  }

  /**
   * Health payload. Not part of the versioned data contract, so it carries no
   * {@code contractVersion}: only operators and the container healthcheck read it.
   *
   * @param time server clock, which also lets a client spot a badly skewed deployment
   */
  public record HealthStatus(String status, String version, Instant time) {}
}
