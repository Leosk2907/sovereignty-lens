package eu.sovereigntylens.mapper;

import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.domain.model.DomainErrorCode;

/**
 * The single translation point between a domain failure and the published error contract.
 *
 * <p>The data contract fixes which HTTP status each code carries. That mapping lives on {@link
 * ApiErrorCode} itself, so once a domain failure has been translated here the status is decided —
 * no handler can report a code with a status the contract does not allow.
 */
public final class ErrorMapper {

  private ErrorMapper() {}

  public static ApiErrorCode toContract(DomainErrorCode code) {
    return switch (code) {
      case VALIDATION_ERROR -> ApiErrorCode.VALIDATION_ERROR;
      case UNAUTHORIZED -> ApiErrorCode.UNAUTHORIZED;
      case FORBIDDEN -> ApiErrorCode.FORBIDDEN;
      case SESSION_NOT_FOUND -> ApiErrorCode.SESSION_NOT_FOUND;
      case SOURCE_NOT_FOUND -> ApiErrorCode.SOURCE_NOT_FOUND;
      case NOT_FOUND -> ApiErrorCode.NOT_FOUND;
      case DUPLICATE_DEPENDENCY -> ApiErrorCode.DUPLICATE_DEPENDENCY;
      case ALREADY_CONTRIBUTED -> ApiErrorCode.ALREADY_CONTRIBUTED;
      case SESSION_PAUSED -> ApiErrorCode.SESSION_PAUSED;
      case ROUND_CAPACITY_REACHED -> ApiErrorCode.ROUND_CAPACITY_REACHED;
      case INTERNAL_ERROR -> ApiErrorCode.INTERNAL_ERROR;
    };
    // No default branch on purpose: adding a domain code without deciding how it reaches a client
    // is a compile error rather than a runtime surprise.
  }
}
