package eu.sovereigntylens.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.domain.port.ContributionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link ContributionRepository}.
 *
 * <p>The whole submission is one {@code select submit_dependency(...)} call, and that is what makes
 * it atomic: the function locks the session row, so the quota check, the one-contribution-per-browser
 * check and the duplicate-edge check all see a stable view, and the {@code dependency.created} event
 * row is written in the same transaction as the dependency. Reassembling those steps in Java would
 * open a window in which two phones both pass the same capacity check.
 */
@Repository
public class JdbcContributionRepository implements ContributionRepository {

  private static final String SUBMIT =
      """
      select submit_dependency(
          :slug,
          :sourceId::uuid,
          :name,
          :normalizedName,
          :type,
          :jurisdiction,
          :contributorHash,
          :capacity)
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcContributionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public SubmissionResult submit(DependencySubmission submission) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("slug", submission.sessionSlug());
    parameters.put("sourceId", submission.sourceOrganizationId().toString());
    parameters.put("name", submission.targetDisplayName());
    parameters.put("normalizedName", submission.targetComparisonKey());
    parameters.put("type", submission.targetOrganizationType().wireValue());
    parameters.put("jurisdiction", submission.targetJurisdiction().wireValue());
    parameters.put("contributorHash", submission.contributorHash());
    parameters.put("capacity", submission.roundCapacity());

    String json;
    try {
      json = jdbc.queryForObject(SUBMIT, parameters, String.class);
    } catch (DataAccessException e) {
      // Only the SL### class carries a business meaning; anything else is a genuine fault and must
      // keep its stack trace rather than be dressed up as a rule violation.
      Optional<DomainException> domainFailure = SqlStateErrors.translate(e);
      if (domainFailure.isPresent()) {
        throw domainFailure.get();
      }
      throw e;
    }
    return parse(json);
  }

  /**
   * A failure here is a bug, not a rule violation, so it stays an unchecked exception: the handler
   * logs it with its stack trace and answers with a generic {@code INTERNAL_ERROR}. Nothing about
   * the function's output reaches the client - it is our own internal shape and would only tell an
   * attacker about the schema.
   */
  private SubmissionResult parse(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalStateException("submit_dependency returned no result");
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      return new SubmissionResult(
          text(root, "eventId"),
          root.path("round").asInt(),
          organization(root.path("node")),
          dependency(root.path("edge")));
    } catch (JsonProcessingException | RuntimeException e) {
      // An unreadable payload, a missing key or an unparseable timestamp all mean the SQL function
      // and this mapper have drifted apart.
      throw new IllegalStateException("submit_dependency returned an unusable result", e);
    }
  }

  private static Organization organization(JsonNode node) {
    return new Organization(
        text(node, "id"),
        text(node, "name"),
        OrganizationType.fromWire(text(node, "organizationType")),
        Jurisdiction.fromWire(text(node, "jurisdiction")),
        node.path("isSeed").asBoolean());
  }

  private static Dependency dependency(JsonNode edge) {
    return new Dependency(
        text(edge, "id"),
        text(edge, "sourceOrganizationId"),
        text(edge, "targetOrganizationId"),
        edge.path("isSeed").asBoolean(),
        DependencyStatus.fromWire(text(edge, "status")),
        Instant.parse(text(edge, "createdAt")));
  }

  private static String text(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (value.isMissingNode() || value.isNull()) {
      throw new IllegalStateException("submit_dependency result is missing " + field);
    }
    return value.asText();
  }
}
