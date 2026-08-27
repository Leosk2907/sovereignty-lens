package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.mapper.ErrorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into the canonical error envelope.
 *
 * <p>Statuses come from {@link ApiErrorCode} rather than from the throw site, so the fixed mapping
 * in the data contract cannot drift as handlers are added.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Body text used whenever echoing the real cause could disclose internals. */
  private static final String GENERIC_INTERNAL_MESSAGE =
      "The service could not complete the request.";

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiErrorResponse> handleDomain(DomainException exception) {
    ApiErrorCode code = ErrorMapper.toContract(exception.code());
    if (code == ApiErrorCode.INTERNAL_ERROR) {
      // An internal failure's message can name SQL functions, columns or keys.
      // It belongs in the log, never in a response an audience member can read.
      log.error("Internal failure", exception);
      return respond(code, GENERIC_INTERNAL_MESSAGE, null);
    }
    return respond(code, exception.getMessage(), exception.field());
  }

  /** Bean-validation failures on a request body. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
    var fieldError = e.getBindingResult().getFieldErrors().stream().findFirst();
    String field = fieldError.map(f -> f.getField()).orElse(null);
    String message =
        fieldError
            .map(f -> f.getDefaultMessage() == null ? "Invalid value." : f.getDefaultMessage())
            .orElse("The request body is invalid.");
    return respond(ApiErrorCode.VALIDATION_ERROR, message, field);
  }

  /**
   * Malformed JSON, an unknown property (the object mapper is strict), or an unparseable enum value.
   * The cause text is not echoed back: it can contain the raw request body.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
    log.debug("Rejected unreadable request body", e);
    return respond(
        ApiErrorCode.VALIDATION_ERROR, "The request body is not valid for contract version 1.", null);
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiErrorResponse> handleBadParameter(Exception e) {
    log.debug("Rejected request parameter", e);
    return respond(
        ApiErrorCode.VALIDATION_ERROR, "A request parameter is missing or invalid.", null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException e) {
    return respond(ApiErrorCode.NOT_FOUND, "No such endpoint.", null);
  }

  /**
   * Spring's own MVC failures, which already know their status.
   *
   * <p>Without these the catch-all below would claim them and report a client mistake as a
   * retryable {@code 500}. A phone that forgot its {@code Content-Type} would then be told to retry
   * a request that can never succeed. The contract's status table has no 405, 406 or 415, so a
   * wrong method is reported as "no such endpoint" and a wrong media type as a validation failure -
   * both truthful, and both non-retryable.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleWrongMethod(
      HttpRequestMethodNotSupportedException e) {
    return respond(ApiErrorCode.NOT_FOUND, "No such endpoint.", null);
  }

  @ExceptionHandler({
    HttpMediaTypeNotSupportedException.class,
    HttpMediaTypeNotAcceptableException.class,
    ServletRequestBindingException.class
  })
  public ResponseEntity<ApiErrorResponse> handleUnsupportedMedia(Exception e) {
    log.debug("Rejected request envelope", e);
    return respond(
        ApiErrorCode.VALIDATION_ERROR,
        "The request must be sent and accepted as application/json.",
        null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    log.error("Unhandled failure", exception);
    return respond(ApiErrorCode.INTERNAL_ERROR, GENERIC_INTERNAL_MESSAGE, null);
  }

  private ResponseEntity<ApiErrorResponse> respond(ApiErrorCode code, String message, String field) {
    return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(code, message, field));
  }
}
