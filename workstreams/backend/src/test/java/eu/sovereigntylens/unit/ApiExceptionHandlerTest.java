package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.adapter.web.ApiExceptionHandler;
import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.mapper.ErrorMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The handler is a plain object; nothing here needs an HTTP server or a Spring context, so these
 * assertions run against the {@link ResponseEntity} it returns.
 */
@DisplayName("ApiExceptionHandler")
class ApiExceptionHandlerTest {

  private static final String GENERIC_INTERNAL_MESSAGE =
      "The service could not complete the request.";

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void rendersADomainFailureAsTheCanonicalEnvelope() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleDomain(
            new DomainException(
                DomainErrorCode.DUPLICATE_DEPENDENCY, "That dependency has already been added."));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().contractVersion()).isEqualTo(1);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.DUPLICATE_DEPENDENCY);
    assertThat(response.getBody().error().message())
        .isEqualTo("That dependency has already been added.");
    assertThat(response.getBody().error().retryable()).isFalse();
    assertThat(response.getBody().error().field()).isNull();
  }

  @Test
  void carriesTheOffendingFieldWhenTheFailureNamesOne() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleDomain(
            DomainException.validation(
                "Company name must be between 2 and 60 characters.", "target.name"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().field()).isEqualTo("target.name");
  }

  @ParameterizedTest
  @EnumSource(
      value = DomainErrorCode.class,
      names = "INTERNAL_ERROR",
      mode = EnumSource.Mode.EXCLUDE)
  void usesTheStatusTheContractFixesForEveryDomainCode(DomainErrorCode code) {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleDomain(new DomainException(code, "Something specific happened."));

    ApiErrorCode expected = ErrorMapper.toContract(code);
    assertThat(response.getStatusCode().value()).isEqualTo(expected.status());
    assertThat(response.getBody().error().code()).isEqualTo(expected);
    assertThat(response.getBody().error().retryable()).isEqualTo(expected.retryable());
  }

  @Test
  void doesNotLeakAnInternalFailureMessageIntoTheResponseBody() {
    // An internal failure's message can name SQL functions, columns or constraint keys. It belongs
    // in the log; an audience member reading the response must only see the generic text.
    DomainException internal =
        new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "ERROR: duplicate key value violates unique constraint "
                + "\"organizations_session_id_normalized_name_key\"");

    ResponseEntity<ApiErrorResponse> response = handler.handleDomain(internal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().error().message()).isEqualTo(GENERIC_INTERNAL_MESSAGE);
    assertThat(response.getBody().error().message())
        .doesNotContain("organizations_session_id_normalized_name_key")
        .doesNotContain("unique constraint");
    assertThat(response.getBody().error().field()).isNull();
    assertThat(response.getBody().error().retryable()).isTrue();
  }

  @Test
  void alsoDropsTheFieldOfAnInternalFailure() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleDomain(
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR, "pg_advisory_xact_lock failed", "target.name"));

    assertThat(response.getBody().error().field()).isNull();
  }

  @Test
  void turnsAnUnexpectedRuntimeFailureIntoAGeneric500() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnexpected(
            new IllegalStateException("connection pool exhausted at jdbc:postgresql://db:5432"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().contractVersion()).isEqualTo(1);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    assertThat(response.getBody().error().message()).isEqualTo(GENERIC_INTERNAL_MESSAGE);
    assertThat(response.getBody().error().message()).doesNotContain("jdbc:postgresql");
    assertThat(response.getBody().error().retryable()).isTrue();
    assertThat(response.getBody().error().field()).isNull();
  }

  @Test
  void reportsAnUnknownEndpointAsNotFoundWithoutEchoingThePath() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleNoResource(
            new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/secret"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.NOT_FOUND);
    assertThat(response.getBody().error().message()).doesNotContain("/api/secret");
  }

  @Test
  void rejectsAnUnreadableBodyAsValidationWithoutEchoingIt() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnreadable(
            new org.springframework.http.converter.HttpMessageNotReadableException(
                "Unrecognized field \"sneaky\"",
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
    assertThat(response.getBody().error().message()).doesNotContain("sneaky");
  }

  @Test
  void rejectsAMissingParameterAsValidation() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleBadParameter(
            new org.springframework.web.bind.MissingServletRequestParameterException(
                "round", "int"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
  }

  /**
   * Spring's own MVC failures used to fall through to the catch-all and reach a phone as a
   * retryable 500, which told it to retry a request that could never succeed. Each one must now
   * carry a non-retryable code the contract's status table actually lists.
   */
  @Test
  void reportsAWrongHttpMethodAsNotFoundRatherThanARetryableFailure() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleWrongMethod(
            new org.springframework.web.HttpRequestMethodNotSupportedException(
                "DELETE", java.util.List.of("GET", "POST")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.NOT_FOUND);
    assertThat(response.getBody().error().retryable()).isFalse();
  }

  @Test
  void rejectsAnUnsupportedRequestContentTypeAsValidation() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnsupportedMedia(
            new org.springframework.web.HttpMediaTypeNotSupportedException(
                org.springframework.http.MediaType.TEXT_PLAIN,
                java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
    assertThat(response.getBody().error().retryable()).isFalse();
  }

  @Test
  void rejectsAnUnacceptableResponseContentTypeAsValidation() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnsupportedMedia(
            new org.springframework.web.HttpMediaTypeNotAcceptableException(
                java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
  }

  @Test
  void rejectsAMissingRequestBindingAsValidation() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnsupportedMedia(
            new org.springframework.web.bind.ServletRequestBindingException("Missing header"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_ERROR);
  }

  @Test
  void marksNoClientMistakeRetryable() {
    // Only a genuine internal fault is worth an automatic retry; everything a client can fix by
    // sending a different request must not loop.
    List<ResponseEntity<ApiErrorResponse>> clientMistakes =
        List.of(
            handler.handleWrongMethod(
                new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE")),
            handler.handleUnsupportedMedia(
                new org.springframework.web.bind.ServletRequestBindingException("Missing header")),
            handler.handleNoResource(
                new NoResourceFoundException(
                    org.springframework.http.HttpMethod.GET, "/api/nothing")),
            handler.handleBadParameter(
                new org.springframework.web.bind.MissingServletRequestParameterException(
                    "round", "int")));

    assertThat(clientMistakes)
        .allSatisfy(
            response -> {
              assertThat(response.getBody().error().retryable()).isFalse();
              assertThat(response.getStatusCode().value()).isLessThan(500);
            });
  }
}
