package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.adapter.web.security.AdminSessionCookie;
import eu.sovereigntylens.application.AdminService;
import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.contract.AdminActionRequest;
import eu.sovereigntylens.contract.AdminActionResult;
import eu.sovereigntylens.contract.AdminDependencyList;
import eu.sovereigntylens.contract.AdminLoginRequest;
import eu.sovereigntylens.contract.AdminLoginResult;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.contract.ContractVersion;
import eu.sovereigntylens.contract.DependencyStatusRequest;
import eu.sovereigntylens.contract.DependencyStatusResult;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.port.AdminRepository;
import eu.sovereigntylens.mapper.AdminMapper;
import eu.sovereigntylens.mapper.GraphMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The presenter API.
 *
 * <p>Only login and logout are reachable without a session cookie; everything else under {@code
 * /api/admin} is gated by {@code AdminAuthInterceptor}, so no handler here repeats an auth check.
 *
 * <p>Nothing in this class logs a password, a secret or a token, and no failure message says which
 * check failed. A presenter typing the wrong password and a script posting a body with no password
 * at all receive the same response, byte for byte.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Presenter authentication and session control.")
public class AdminController {

  private final AdminService adminService;
  private final AdminSessionCookie sessionCookie;
  private final byte[] adminPassword;

  public AdminController(
      AdminService adminService, AdminSessionCookie sessionCookie, AppProperties properties) {
    this.adminService = adminService;
    this.sessionCookie = sessionCookie;
    this.adminPassword = properties.adminPassword().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Exchanges the shared presenter password for a session cookie.
   *
   * <p>The request body is not bean-validated. A missing or blank password has to fail exactly as a
   * wrong one does, and a validation error naming the {@code password} field would tell a prober
   * that its body shape was right and only the value was wrong.
   */
  @Operation(
      summary = "Log in as the presenter",
      description =
          "Validates the shared password and sets the signed, HttpOnly session cookie. "
              + "Failures are reported as a generic 401 that never reveals which check failed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Authenticated; the session cookie is set."),
    @ApiResponse(
        responseCode = "400",
        description = "Unsupported contract version.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Authentication failed.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping("/login")
  public ResponseEntity<AdminLoginResult> login(
      @RequestBody AdminLoginRequest request, HttpServletRequest httpRequest) {
    requireContractVersion(request.contractVersion());
    if (!matchesAdminPassword(request.password())) {
      throw DomainException.unauthorized();
    }
    String setCookie =
        sessionCookie.toCookie(sessionCookie.issue(), httpRequest.isSecure()).toString();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, setCookie)
        .body(AdminLoginResult.ok());
  }

  @Operation(
      summary = "Log out",
      description = "Clears the session cookie. Succeeds whether or not a session was present.")
  @ApiResponse(responseCode = "204", description = "The session cookie is cleared.")
  @PostMapping("/logout")
  public ResponseEntity<Void> logout() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, sessionCookie.expiredCookie().toString())
        .build();
  }

  @Operation(
      summary = "Run a presenter action",
      description =
          "Pause, resume, undo the newest audience dependency, or start the next round. "
              + "Every action emits graph.invalidated in the same transaction.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The action was applied."),
    @ApiResponse(
        responseCode = "400",
        description = "Unsupported contract version or unknown action.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "No valid presenter session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Unknown session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PostMapping("/sessions/{slug}/actions")
  public AdminActionResult act(
      @PathVariable String slug, @Valid @RequestBody AdminActionRequest request) {
    requireContractVersion(request.contractVersion());
    AdminOutcome outcome =
        switch (request.action().type()) {
          case PAUSE -> adminService.pause(slug);
          case RESUME -> adminService.resume(slug);
          case RESET -> adminService.reset(slug);
          case UNDO -> adminService.undo(slug);
        };
    return AdminMapper.toContract(outcome);
  }

  /**
   * The presenter's review list.
   *
   * <p>{@code no-store} rather than a short max-age: this list is the basis for hiding something
   * off the screen, and acting on a cached copy would hide the wrong row.
   */
  @Operation(
      summary = "List current-round dependencies",
      description =
          "All current-round, non-seed dependencies including hidden entries, newest first.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The review list."),
    @ApiResponse(
        responseCode = "401",
        description = "No valid presenter session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Unknown session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping("/sessions/{slug}/dependencies")
  public ResponseEntity<AdminDependencyList> dependencies(@PathVariable String slug) {
    AdminService.DependencyListing listing = adminService.listDependencies(slug);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AdminMapper.toContract(listing.session(), listing.dependencies()));
  }

  @Operation(
      summary = "Hide or restore a dependency",
      description =
          "Only a current-round, non-seed dependency can be changed; anything else is reported as "
              + "404. Restoring an edge that is already active in this round is a 409.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The dependency was updated."),
    @ApiResponse(
        responseCode = "400",
        description = "Unsupported contract version or malformed dependency id.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "No valid presenter session.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No such dependency in the current round.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Restoring would duplicate an active edge.",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @PatchMapping("/dependencies/{id}")
  public DependencyStatusResult setDependencyStatus(
      @PathVariable String id, @Valid @RequestBody DependencyStatusRequest request) {
    requireContractVersion(request.contractVersion());
    AdminRepository.DependencyOutcome outcome =
        adminService.setDependencyStatus(dependencyId(id), AdminMapper.toDomain(request.status()));
    return DependencyStatusResult.of(
        outcome.eventId(), GraphMapper.toContract(outcome.dependency()));
  }

  /**
   * Compares the submitted password without leaking how far it matched.
   *
   * <p>{@link MessageDigest#isEqual} does not short-circuit on the first differing byte, so the
   * time taken carries no information about the password's content.
   */
  private boolean matchesAdminPassword(String submitted) {
    byte[] candidate =
        submitted == null ? new byte[0] : submitted.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(adminPassword, candidate);
  }

  /** A path variable is caller-controlled text; an unparseable id is a bad request, not a crash. */
  private static UUID dependencyId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException notAUuid) {
      throw DomainException.validation("Dependency id must be a UUID.", "id");
    }
  }

  private static void requireContractVersion(Integer contractVersion) {
    if (contractVersion == null || contractVersion != ContractVersion.CURRENT) {
      throw DomainException.validation(
          "This API speaks contract version " + ContractVersion.CURRENT + ".", "contractVersion");
    }
  }
}
