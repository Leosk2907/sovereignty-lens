package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform error envelope for every endpoint. */
public record ApiErrorResponse(int contractVersion, Error error) {

  /** {@code field} is omitted unless the failure is attributable to one request field. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Error(ApiErrorCode code, String message, boolean retryable, String field) {}

  public static ApiErrorResponse of(ApiErrorCode code, String message, String field) {
    return new ApiErrorResponse(
        ContractVersion.CURRENT, new Error(code, message, code.retryable(), field));
  }

  public static ApiErrorResponse of(ApiErrorCode code, String message) {
    return of(code, message, null);
  }
}
