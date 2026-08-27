package eu.sovereigntylens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.adapter.web.security.AdminSessionCookie;
import eu.sovereigntylens.support.AbstractDatabaseTest;
import eu.sovereigntylens.support.DatabaseFixtures.SessionFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The status-code contract, exercised over real HTTP against the assembled service.
 *
 * <p>Assertions are on the status and on {@code error.code} only. The messages are audience-facing
 * copy that a designer may reword at any time; pinning them here would turn a wording change into a
 * failing build for no gain.
 *
 * <p>The round capacity is lowered to two so the "round is full" answer can be provoked with three
 * requests instead of a hundred and fifty. That means no test in this class may make more than two
 * accepted contributions to one session.
 */
@DisplayName("HTTP contract")
@TestPropertySource(properties = "app.round-capacity=2")
class ApiEndpointIT extends AbstractDatabaseTest {

  private static final String ADMIN_PASSWORD = "test-presenter-password";

  @Autowired private TestRestTemplate rest;

  @Autowired private ObjectMapper objectMapper;

  private SessionFixture session;

  @BeforeEach
  void createSession() {
    session = fixtures.seededSession();
  }

  @Test
  void createsAContribution() throws Exception {
    ResponseEntity<String> response =
        contribute(session.supplier().toString(), "Nimbus Cloud Co", UUID.randomUUID().toString());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(body.path("eventId").asText()).isNotBlank();
    assertThat(body.path("round").asInt()).isEqualTo(1);

    JsonNode node = body.path("node");
    assertThat(node.path("id").asText()).isNotBlank();
    assertThat(node.path("name").asText()).isEqualTo("Nimbus Cloud Co");
    assertThat(node.path("organizationType").asText()).isEqualTo("cloud");
    assertThat(node.path("jurisdiction").asText()).isEqualTo("united_states");
    assertThat(node.path("isSeed").asBoolean()).isFalse();

    JsonNode edge = body.path("edge");
    assertThat(edge.path("id").asText()).isNotBlank();
    assertThat(edge.path("sourceOrganizationId").asText()).isEqualTo(session.supplier().toString());
    assertThat(edge.path("targetOrganizationId").asText()).isEqualTo(node.path("id").asText());
    assertThat(edge.path("isSeed").asBoolean()).isFalse();
    assertThat(edge.path("status").asText()).isEqualTo("active");
    // RFC 3339 rather than an epoch number, per the contract's timestamp rule.
    assertThat(edge.path("createdAt").asText()).endsWith("Z");
  }

  @Test
  void servesTheSnapshotForAKnownSession() throws Exception {
    ResponseEntity<String> response =
        rest.getForEntity("/api/sessions/{slug}/graph", String.class, session.slug());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(body.path("session").path("slug").asText()).isEqualTo(session.slug());
    assertThat(body.path("session").path("status").asText()).isEqualTo("open");
    assertThat(body.path("nodes").size()).isEqualTo(4);
    assertThat(body.path("edges").size()).isEqualTo(3);
    assertThat(body.path("serverTime").asText()).isNotBlank();
  }

  @Test
  void rejectsACompanyNameThatIsTooShort() throws Exception {
    ResponseEntity<String> response =
        contribute(session.supplier().toString(), "A", UUID.randomUUID().toString());

    assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
  }

  @Test
  void rejectsAnUnsupportedContractVersion() throws Exception {
    String body =
        """
        {"contractVersion":99,"anonymousClientId":"%s","sourceOrganizationId":"%s",\
        "target":{"name":"Nimbus Cloud Co","organizationType":"cloud",\
        "jurisdiction":"united_states"}}"""
            .formatted(UUID.randomUUID(), session.supplier());

    assertError(
        post("/api/sessions/{slug}/dependencies", body, jsonHeaders(), session.slug()),
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR");
  }

  @Test
  void rejectsAMalformedSourceOrganizationId() throws Exception {
    ResponseEntity<String> response =
        contribute("not-a-uuid", "Nimbus Cloud Co", UUID.randomUUID().toString());

    assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
  }

  @Test
  void reportsAnUnknownSessionAsNotFound() throws Exception {
    String body = contributionBody(session.supplier().toString(), "Nimbus Cloud Co",
        UUID.randomUUID().toString());

    assertError(
        post("/api/sessions/{slug}/dependencies", body, jsonHeaders(), "no-such-session"),
        HttpStatus.NOT_FOUND,
        "SESSION_NOT_FOUND");
  }

  @Test
  void reportsAnUnknownSourceOrganizationAsNotFound() throws Exception {
    ResponseEntity<String> response =
        contribute(UUID.randomUUID().toString(), "Nimbus Cloud Co", UUID.randomUUID().toString());

    JsonNode error = assertError(response, HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND");
    assertThat(error.path("field").asText()).isEqualTo("sourceOrganizationId");
  }

  @Test
  void reportsASecondContributionFromOneBrowserAsAConflict() throws Exception {
    String browser = UUID.randomUUID().toString();
    assertThat(contribute(session.supplier().toString(), "Nimbus Cloud Co", browser).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> second =
        contribute(session.carrier().toString(), "Meridian Analytics", browser);

    assertError(second, HttpStatus.CONFLICT, "ALREADY_CONTRIBUTED");
  }

  @Test
  void reportsADuplicateEdgeAsAConflict() throws Exception {
    assertThat(
            contribute(
                    session.supplier().toString(),
                    "Nimbus Cloud Co",
                    UUID.randomUUID().toString())
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> duplicate =
        contribute(session.supplier().toString(), "Nimbus Cloud Co", UUID.randomUUID().toString());

    assertError(duplicate, HttpStatus.CONFLICT, "DUPLICATE_DEPENDENCY");
  }

  @Test
  void reportsAPausedSessionAsLocked() throws Exception {
    fixtures.pauseSession(session.slug());

    ResponseEntity<String> response =
        contribute(session.supplier().toString(), "Nimbus Cloud Co", UUID.randomUUID().toString());

    assertError(response, HttpStatus.LOCKED, "SESSION_PAUSED");
  }

  @Test
  void reportsAFullRoundAsTooManyRequests() throws Exception {
    // Two is this class's configured capacity, so the third request is the one under test.
    assertThat(
            contribute(session.root().toString(), "First Vendor", UUID.randomUUID().toString())
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(
            contribute(session.supplier().toString(), "Second Vendor", UUID.randomUUID().toString())
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> full =
        contribute(session.carrier().toString(), "Third Vendor", UUID.randomUUID().toString());

    assertError(full, HttpStatus.TOO_MANY_REQUESTS, "ROUND_CAPACITY_REACHED");
  }

  @Test
  void refusesAdminActionsWithoutASessionCookie() throws Exception {
    assertError(
        post(
            "/api/admin/sessions/{slug}/actions",
            """
            {"contractVersion":1,"action":{"type":"pause"}}""",
            jsonHeaders(),
            session.slug()),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED");

    assertError(
        rest.getForEntity(
            "/api/admin/sessions/{slug}/dependencies", String.class, session.slug()),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED");
  }

  @Test
  void refusesTheWrongAdminPassword() throws Exception {
    assertError(
        post(
            "/api/admin/login",
            """
            {"contractVersion":1,"password":"not-the-password"}""",
            jsonHeaders()),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED");
  }

  @Test
  void letsThePresenterLogInRunAnActionAndLogOut() throws Exception {
    ResponseEntity<String> login =
        post(
            "/api/admin/login",
            """
            {"contractVersion":1,"password":"%s"}""".formatted(ADMIN_PASSWORD),
            jsonHeaders());

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode loginBody = objectMapper.readTree(login.getBody());
    assertThat(loginBody.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(loginBody.path("authenticated").asBoolean()).isTrue();

    String cookie = sessionCookie(login);
    assertThat(cookie).startsWith(AdminSessionCookie.NAME + "=v1.");

    HttpHeaders authorized = jsonHeaders();
    authorized.add(HttpHeaders.COOKIE, cookie);
    ResponseEntity<String> action =
        post(
            "/api/admin/sessions/{slug}/actions",
            """
            {"contractVersion":1,"action":{"type":"pause"}}""",
            authorized,
            session.slug());

    assertThat(action.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode actionBody = objectMapper.readTree(action.getBody());
    assertThat(actionBody.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(actionBody.path("eventId").asText()).isNotBlank();
    assertThat(actionBody.path("session").path("status").asText()).isEqualTo("paused");
    assertThat(actionBody.path("session").path("currentRound").asInt()).isEqualTo(1);
    assertThat(fixtures.sessionStatus(session.slug())).isEqualTo("paused");

    HttpHeaders withCookie = new HttpHeaders();
    withCookie.add(HttpHeaders.COOKIE, cookie);
    ResponseEntity<String> logout =
        rest.exchange(
            "/api/admin/logout", HttpMethod.POST, new HttpEntity<>(null, withCookie), String.class);

    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(sessionCookie(logout)).isEqualTo(AdminSessionCookie.NAME + "=");

    // The cleared cookie is the one the browser will send next; it must not authenticate anything.
    HttpHeaders cleared = jsonHeaders();
    cleared.add(HttpHeaders.COOKIE, AdminSessionCookie.NAME + "=");
    assertError(
        post(
            "/api/admin/sessions/{slug}/actions",
            """
            {"contractVersion":1,"action":{"type":"resume"}}""",
            cleared,
            session.slug()),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHORIZED");
  }

  private ResponseEntity<String> contribute(
      String sourceOrganizationId, String targetName, String anonymousClientId) {
    return post(
        "/api/sessions/{slug}/dependencies",
        contributionBody(sourceOrganizationId, targetName, anonymousClientId),
        jsonHeaders(),
        session.slug());
  }

  private static String contributionBody(
      String sourceOrganizationId, String targetName, String anonymousClientId) {
    return """
        {"contractVersion":1,"anonymousClientId":"%s","sourceOrganizationId":"%s",\
        "target":{"name":"%s","organizationType":"cloud","jurisdiction":"united_states"}}"""
        .formatted(anonymousClientId, sourceOrganizationId, targetName);
  }

  private ResponseEntity<String> post(
      String path, String body, HttpHeaders headers, Object... uriVariables) {
    return rest.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class, uriVariables);
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  /** @return the {@code error} object, so a caller can assert on {@code field} as well */
  private JsonNode assertError(ResponseEntity<String> response, HttpStatus status, String code)
      throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(status);
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.path("contractVersion").asInt()).isEqualTo(1);

    JsonNode error = body.path("error");
    assertThat(error.path("code").asText()).isEqualTo(code);
    assertThat(error.path("retryable").isBoolean()).isTrue();
    // The message must exist but its wording is not part of the contract.
    assertThat(error.path("message").asText()).isNotBlank();
    return error;
  }

  private static String sessionCookie(ResponseEntity<String> response) {
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookies).isNotNull();
    return setCookies.stream()
        .filter(value -> value.startsWith(AdminSessionCookie.NAME + "="))
        .findFirst()
        .map(value -> value.split(";", 2)[0])
        .orElseThrow(() -> new AssertionError("No " + AdminSessionCookie.NAME + " cookie was set"));
  }
}
