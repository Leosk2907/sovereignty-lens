package eu.sovereigntylens.domain;

import eu.sovereigntylens.domain.model.DomainErrorCode;

/**
 * A business rule was violated.
 *
 * <p>Carries a {@link DomainErrorCode} and, when the failure is attributable to one input, the name
 * of that field. It knows nothing about HTTP: the web adapter translates it.
 */
public class DomainException extends RuntimeException {

  private final DomainErrorCode code;
  private final String field;

  public DomainException(DomainErrorCode code, String message) {
    this(code, message, null, null);
  }

  public DomainException(DomainErrorCode code, String message, String field) {
    this(code, message, field, null);
  }

  public DomainException(DomainErrorCode code, String message, String field, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.field = field;
  }

  public DomainErrorCode code() {
    return code;
  }

  /** The offending input field, or null when the failure is not field-specific. */
  public String field() {
    return field;
  }

  public static DomainException validation(String message, String field) {
    return new DomainException(DomainErrorCode.VALIDATION_ERROR, message, field);
  }

  public static DomainException sessionNotFound(String slug) {
    return new DomainException(DomainErrorCode.SESSION_NOT_FOUND, "Unknown session: " + slug);
  }

  public static DomainException notFound(String message) {
    return new DomainException(DomainErrorCode.NOT_FOUND, message);
  }

  /** Deliberately generic: an authentication failure never reveals which check failed. */
  public static DomainException unauthorized() {
    return new DomainException(DomainErrorCode.UNAUTHORIZED, "Authentication required.");
  }
}
