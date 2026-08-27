package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical error codes with their fixed HTTP status mapping and retry semantics.
 *
 * <p>The status is a plain {@code int} rather than a framework type on purpose: this package is a
 * published artefact shared with three frontend workstreams, and it must not drag a web framework
 * onto their classpath. The mapping itself belongs here because the data contract defines it.
 */
public enum ApiErrorCode {
  VALIDATION_ERROR(400, false),
  UNAUTHORIZED(401, false),
  FORBIDDEN(403, false),
  SESSION_NOT_FOUND(404, false),
  SOURCE_NOT_FOUND(404, false),
  NOT_FOUND(404, false),
  DUPLICATE_DEPENDENCY(409, false),
  ALREADY_CONTRIBUTED(409, false),
  SESSION_PAUSED(423, false),
  ROUND_CAPACITY_REACHED(429, false),
  INTERNAL_ERROR(500, true);

  private final int status;
  private final boolean retryable;

  ApiErrorCode(int status, boolean retryable) {
    this.status = status;
    this.retryable = retryable;
  }

  @JsonValue
  public String wireValue() {
    return name();
  }

  /** The HTTP status the data contract fixes for this code. */
  public int status() {
    return status;
  }

  /** Only transient internal failures are worth an automatic client retry. */
  public boolean retryable() {
    return retryable;
  }
}
