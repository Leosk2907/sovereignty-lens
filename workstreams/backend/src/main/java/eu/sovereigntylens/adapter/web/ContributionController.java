package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.application.ContributionService;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.contract.ContractVersion;
import eu.sovereigntylens.contract.ContributionRequest;
import eu.sovereigntylens.contract.ContributionResult;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.ContributionCommand;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.mapper.GraphMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audience-facing write endpoint.
 *
 * <p>Nothing but translation happens here: the contract version is a wire concern and is checked in
 * this class, everything else is decided by {@link ContributionService} in domain terms and mapped
 * back through {@link GraphMapper}. Failures are thrown as {@link DomainException} and turned into
 * the canonical error envelope by {@code ApiExceptionHandler}, so this class never builds an error
 * response itself and cannot report a status the data contract forbids.
 */
@RestController
@Tag(name = "Contributions", description = "Audience submissions")
public class ContributionController {

  private final ContributionService contributions;

  public ContributionController(ContributionService contributions) {
    this.contributions = contributions;
  }

  @Operation(
      summary = "Add a dependency",
      description =
          """
          Records that the selected source organization depends on the named target, and emits the \
          matching dependency.created live event in the same transaction. The response carries the \
          canonical node and edge; a client that also receives the event applies both once, keyed \
          on eventId.

          One browser may contribute once per round, a target may not be a public body, and a \
          target organization already present in the session is reused rather than duplicated.""")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "The dependency was recorded."),
    @ApiResponse(
        responseCode = "400",
        description =
            "VALIDATION_ERROR: unsupported contract version, malformed identifier, company name"
                + " outside 2-60 characters, or a public body named as the target.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "SESSION_NOT_FOUND or SOURCE_NOT_FOUND: unknown slug, or a source that is"
            + " not part of that session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "ALREADY_CONTRIBUTED: this browser already submitted in the current round."
                + " DUPLICATE_DEPENDENCY: the same edge is already active in the round.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "423",
        description = "SESSION_PAUSED: the presenter has closed submissions.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "429",
        description = "ROUND_CAPACITY_REACHED: the round is full.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping(
      path = "/api/sessions/{slug}/dependencies",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ContributionResult> contribute(
      @PathVariable String slug, @Valid @RequestBody ContributionRequest request) {
    requireCurrentContractVersion(request.contractVersion());

    SubmissionResult result = contributions.contribute(toCommand(slug, request));

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ContributionResult.of(
                result.eventId(),
                result.round(),
                GraphMapper.toContract(result.targetNode()),
                GraphMapper.toContract(result.edge())));
  }

  private static ContributionCommand toCommand(String slug, ContributionRequest request) {
    ContributionRequest.Target target = request.target();
    return new ContributionCommand(
        slug,
        request.anonymousClientId(),
        request.sourceOrganizationId(),
        target.name(),
        GraphMapper.toDomain(target.organizationType()),
        GraphMapper.toDomain(target.jurisdiction()));
  }

  /**
   * A client on an older contract is rejected before any work happens: silently accepting it would
   * mean guessing which fields it meant, and this endpoint writes.
   */
  private static void requireCurrentContractVersion(Integer submitted) {
    if (submitted == null || submitted != ContractVersion.CURRENT) {
      throw DomainException.validation("Unsupported contract version.", "contractVersion");
    }
  }
}
