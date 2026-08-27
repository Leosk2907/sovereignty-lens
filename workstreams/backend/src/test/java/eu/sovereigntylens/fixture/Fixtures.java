package eu.sovereigntylens.fixture;

import eu.sovereigntylens.contract.AdminActionResult;
import eu.sovereigntylens.contract.AdminActionType;
import eu.sovereigntylens.contract.AdminDependency;
import eu.sovereigntylens.contract.AdminDependencyList;
import eu.sovereigntylens.contract.AdminInvalidationReason;
import eu.sovereigntylens.contract.AdminLoginRequest;
import eu.sovereigntylens.contract.AdminLoginResult;
import eu.sovereigntylens.contract.ApiErrorCode;
import eu.sovereigntylens.contract.ApiErrorResponse;
import eu.sovereigntylens.contract.ContributionRequest;
import eu.sovereigntylens.contract.ContributionResult;
import eu.sovereigntylens.contract.DependencyCreatedEvent;
import eu.sovereigntylens.contract.DependencyStatus;
import eu.sovereigntylens.contract.DependencyStatusRequest;
import eu.sovereigntylens.contract.DependencyStatusResult;
import eu.sovereigntylens.contract.GraphEdge;
import eu.sovereigntylens.contract.GraphInvalidatedEvent;
import eu.sovereigntylens.contract.GraphNode;
import eu.sovereigntylens.contract.GraphSnapshot;
import eu.sovereigntylens.contract.Jurisdiction;
import eu.sovereigntylens.contract.OrganizationType;
import eu.sovereigntylens.contract.SessionStatus;
import eu.sovereigntylens.contract.SessionSummary;
import eu.sovereigntylens.domain.model.ContributionCommand;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.GraphView;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.model.SubmissionResult;
import java.time.Instant;
import java.util.List;

/**
 * Deterministic test data for the version-1 contract.
 *
 * <p>These are the Java equivalent of the shared contract fixtures the plan asks for: the same
 * canonical success, error and event shapes the TypeScript workstreams parse with their Zod
 * schemas, so both sides can be shown to agree on one set of bytes.
 *
 * <p>Identifiers are the fixed literals planted by {@code V3__seed_demo_session.sql}, so an
 * integration test may assert against a fixture without querying the seed first. Every timestamp is
 * a constant: nothing here reads a clock, so no assertion built on a fixture can flake.
 *
 * <p>Deliberately free of JUnit, AssertJ, Mockito and Spring so that any suite - unit, integration
 * or a future contract-verification tool - can depend on it.
 */
public final class Fixtures {

  // ---------------------------------------------------------------- seed identifiers (V3)

  public static final String SESSION_ID = "00000000-0000-4000-8000-000000000001";
  public static final String SESSION_SLUG = "demo";
  public static final String SESSION_TITLE = "Sovereignty Lens live demo";
  public static final int CURRENT_ROUND = 1;

  public static final String ROOT_ORGANIZATION_ID = "00000000-0000-4000-8000-000000000101";
  public static final String ALPINE_ID = "00000000-0000-4000-8000-000000000102";
  public static final String BALTIC_ID = "00000000-0000-4000-8000-000000000103";
  public static final String RHINE_ID = "00000000-0000-4000-8000-000000000104";

  public static final String SEED_EDGE_ROOT_TO_ALPINE_ID = "00000000-0000-4000-8000-000000000201";
  public static final String SEED_EDGE_ROOT_TO_RHINE_ID = "00000000-0000-4000-8000-000000000202";
  public static final String SEED_EDGE_ALPINE_TO_BALTIC_ID = "00000000-0000-4000-8000-000000000203";

  // ------------------------------------------------------- audience contribution identifiers

  /** The organization an audience member names; absent from the seed on purpose. */
  public static final String CONTRIBUTED_NODE_ID = "00000000-0000-4000-8000-000000000105";

  public static final String CONTRIBUTED_EDGE_ID = "00000000-0000-4000-8000-000000000205";
  public static final String CONTRIBUTED_NAME = "Northwind Cloud";

  public static final String DEPENDENCY_CREATED_EVENT_ID =
      "00000000-0000-4000-8000-000000000301";
  public static final String GRAPH_INVALIDATED_EVENT_ID =
      "00000000-0000-4000-8000-000000000302";

  /** A browser identifier. Fixed rather than random so a hash assertion is reproducible. */
  public static final String ANONYMOUS_CLIENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

  // ------------------------------------------------------------------------ fixed instants

  public static final Instant SEED_CREATED_AT = Instant.parse("2026-03-01T09:00:00Z");
  public static final Instant CONTRIBUTION_CREATED_AT = Instant.parse("2026-03-01T10:15:30Z");
  public static final Instant SERVER_TIME = Instant.parse("2026-03-01T10:15:31Z");

  private Fixtures() {}

  // ------------------------------------------------------------------------ contract shapes

  public static SessionSummary sessionSummary() {
    return new SessionSummary(
        SESSION_ID,
        SESSION_SLUG,
        SESSION_TITLE,
        SessionStatus.OPEN,
        CURRENT_ROUND,
        ROOT_ORGANIZATION_ID);
  }

  public static GraphNode rootNode() {
    return new GraphNode(
        ROOT_ORGANIZATION_ID,
        "European Digital Services Agency",
        OrganizationType.GOVERNMENT,
        Jurisdiction.EUROPE,
        true);
  }

  public static GraphNode alpineNode() {
    return new GraphNode(
        ALPINE_ID, "Alpine Civic Systems", OrganizationType.SOFTWARE, Jurisdiction.EUROPE, true);
  }

  public static GraphNode balticNode() {
    return new GraphNode(
        BALTIC_ID, "Baltic Data Works", OrganizationType.CLOUD, Jurisdiction.EUROPE, true);
  }

  public static GraphNode rhineNode() {
    return new GraphNode(
        RHINE_ID, "Rhine Public Networks", OrganizationType.TELECOM, Jurisdiction.EUROPE, true);
  }

  /** The audience-added node: the first external jurisdiction in the graph. */
  public static GraphNode contributedNode() {
    return new GraphNode(
        CONTRIBUTED_NODE_ID,
        CONTRIBUTED_NAME,
        OrganizationType.CLOUD,
        Jurisdiction.UNITED_STATES,
        false);
  }

  public static List<GraphNode> seedNodes() {
    return List.of(rootNode(), alpineNode(), balticNode(), rhineNode());
  }

  public static GraphEdge seedEdgeRootToAlpine() {
    return new GraphEdge(
        SEED_EDGE_ROOT_TO_ALPINE_ID,
        ROOT_ORGANIZATION_ID,
        ALPINE_ID,
        true,
        DependencyStatus.ACTIVE,
        SEED_CREATED_AT);
  }

  public static GraphEdge seedEdgeRootToRhine() {
    return new GraphEdge(
        SEED_EDGE_ROOT_TO_RHINE_ID,
        ROOT_ORGANIZATION_ID,
        RHINE_ID,
        true,
        DependencyStatus.ACTIVE,
        SEED_CREATED_AT);
  }

  public static GraphEdge seedEdgeAlpineToBaltic() {
    return new GraphEdge(
        SEED_EDGE_ALPINE_TO_BALTIC_ID,
        ALPINE_ID,
        BALTIC_ID,
        true,
        DependencyStatus.ACTIVE,
        SEED_CREATED_AT);
  }

  public static List<GraphEdge> seedEdges() {
    return List.of(seedEdgeRootToAlpine(), seedEdgeRootToRhine(), seedEdgeAlpineToBaltic());
  }

  public static GraphEdge contributedEdge() {
    return new GraphEdge(
        CONTRIBUTED_EDGE_ID,
        BALTIC_ID,
        CONTRIBUTED_NODE_ID,
        false,
        DependencyStatus.ACTIVE,
        CONTRIBUTION_CREATED_AT);
  }

  /** Seed graph only, as the presentation renders it before the first contribution lands. */
  public static GraphSnapshot seedSnapshot() {
    return GraphSnapshot.of(sessionSummary(), seedNodes(), seedEdges(), SERVER_TIME);
  }

  /** Seed graph plus one audience contribution; every edge still references a present node. */
  public static GraphSnapshot snapshotWithContribution() {
    List<GraphNode> nodes =
        List.of(rootNode(), alpineNode(), balticNode(), rhineNode(), contributedNode());
    List<GraphEdge> edges =
        List.of(
            seedEdgeRootToAlpine(),
            seedEdgeRootToRhine(),
            seedEdgeAlpineToBaltic(),
            contributedEdge());
    return GraphSnapshot.of(sessionSummary(), nodes, edges, SERVER_TIME);
  }

  public static ContributionRequest contributionRequest() {
    return new ContributionRequest(
        1,
        ANONYMOUS_CLIENT_ID,
        BALTIC_ID,
        new ContributionRequest.Target(
            CONTRIBUTED_NAME, OrganizationType.CLOUD, Jurisdiction.UNITED_STATES));
  }

  public static ContributionResult contributionResult() {
    return ContributionResult.of(
        DEPENDENCY_CREATED_EVENT_ID, CURRENT_ROUND, contributedNode(), contributedEdge());
  }

  // -------------------------------------------------------------------------- error shapes

  /** A field-attributable failure: {@code field} is present in the envelope. */
  public static ApiErrorResponse validationError() {
    return ApiErrorResponse.of(
        ApiErrorCode.VALIDATION_ERROR,
        "Company name must be between 2 and 60 characters.",
        "target.name");
  }

  /** The only retryable code, and the one whose message must stay generic. */
  public static ApiErrorResponse internalError() {
    return ApiErrorResponse.of(
        ApiErrorCode.INTERNAL_ERROR, "The service could not complete the request.");
  }

  /**
   * One envelope per canonical code, in declaration order, so a consumer test can parse every error
   * shape the API can produce.
   */
  public static List<ApiErrorResponse> everyErrorShape() {
    return List.of(
        validationError(),
        ApiErrorResponse.of(ApiErrorCode.UNAUTHORIZED, "Authentication required."),
        ApiErrorResponse.of(ApiErrorCode.FORBIDDEN, "This action is not allowed."),
        ApiErrorResponse.of(ApiErrorCode.SESSION_NOT_FOUND, "Unknown session: demo"),
        ApiErrorResponse.of(
            ApiErrorCode.SOURCE_NOT_FOUND,
            "The selected organization is not part of this session.",
            "sourceOrganizationId"),
        ApiErrorResponse.of(ApiErrorCode.NOT_FOUND, "No such endpoint."),
        ApiErrorResponse.of(
            ApiErrorCode.DUPLICATE_DEPENDENCY, "That dependency has already been added."),
        ApiErrorResponse.of(
            ApiErrorCode.ALREADY_CONTRIBUTED, "This device has already contributed this round."),
        ApiErrorResponse.of(ApiErrorCode.SESSION_PAUSED, "The session is paused."),
        ApiErrorResponse.of(
            ApiErrorCode.ROUND_CAPACITY_REACHED, "This round is full."),
        internalError());
  }

  // -------------------------------------------------------------------------- event shapes

  public static DependencyCreatedEvent dependencyCreatedEvent() {
    return DependencyCreatedEvent.of(
        DEPENDENCY_CREATED_EVENT_ID,
        SESSION_SLUG,
        CURRENT_ROUND,
        contributedNode(),
        contributedEdge(),
        CONTRIBUTION_CREATED_AT);
  }

  public static GraphInvalidatedEvent graphInvalidatedEvent() {
    return GraphInvalidatedEvent.of(
        GRAPH_INVALIDATED_EVENT_ID,
        SESSION_SLUG,
        CURRENT_ROUND,
        AdminInvalidationReason.PAUSE,
        CONTRIBUTION_CREATED_AT);
  }

  // -------------------------------------------------------------------------- admin shapes

  public static AdminLoginRequest adminLoginRequest() {
    return new AdminLoginRequest(1, "test-presenter-password");
  }

  public static AdminLoginResult adminLoginResult() {
    return AdminLoginResult.ok();
  }

  public static AdminActionType adminActionType() {
    return AdminActionType.PAUSE;
  }

  public static AdminActionResult adminActionResult() {
    return AdminActionResult.of(
        GRAPH_INVALIDATED_EVENT_ID,
        new SessionSummary(
            SESSION_ID,
            SESSION_SLUG,
            SESSION_TITLE,
            SessionStatus.PAUSED,
            CURRENT_ROUND,
            ROOT_ORGANIZATION_ID));
  }

  public static DependencyStatusRequest dependencyStatusRequest() {
    return new DependencyStatusRequest(1, DependencyStatus.HIDDEN);
  }

  public static DependencyStatusResult dependencyStatusResult() {
    GraphEdge hidden =
        new GraphEdge(
            CONTRIBUTED_EDGE_ID,
            BALTIC_ID,
            CONTRIBUTED_NODE_ID,
            false,
            DependencyStatus.HIDDEN,
            CONTRIBUTION_CREATED_AT);
    return DependencyStatusResult.of(GRAPH_INVALIDATED_EVENT_ID, hidden);
  }

  public static AdminDependency adminDependency() {
    return new AdminDependency(contributedEdge(), balticNode(), contributedNode());
  }

  public static AdminDependencyList adminDependencyList() {
    return AdminDependencyList.of(sessionSummary(), List.of(adminDependency()));
  }

  // ------------------------------------------------------------------------- domain shapes

  public static Session domainSession() {
    return new Session(
        SESSION_ID,
        SESSION_SLUG,
        SESSION_TITLE,
        eu.sovereigntylens.domain.model.SessionStatus.OPEN,
        CURRENT_ROUND,
        ROOT_ORGANIZATION_ID);
  }

  public static Organization domainRootOrganization() {
    return new Organization(
        ROOT_ORGANIZATION_ID,
        "European Digital Services Agency",
        eu.sovereigntylens.domain.model.OrganizationType.GOVERNMENT,
        eu.sovereigntylens.domain.model.Jurisdiction.EUROPE,
        true);
  }

  public static Organization domainAlpineOrganization() {
    return new Organization(
        ALPINE_ID,
        "Alpine Civic Systems",
        eu.sovereigntylens.domain.model.OrganizationType.SOFTWARE,
        eu.sovereigntylens.domain.model.Jurisdiction.EUROPE,
        true);
  }

  public static Organization domainBalticOrganization() {
    return new Organization(
        BALTIC_ID,
        "Baltic Data Works",
        eu.sovereigntylens.domain.model.OrganizationType.CLOUD,
        eu.sovereigntylens.domain.model.Jurisdiction.EUROPE,
        true);
  }

  public static Organization domainContributedOrganization() {
    return new Organization(
        CONTRIBUTED_NODE_ID,
        CONTRIBUTED_NAME,
        eu.sovereigntylens.domain.model.OrganizationType.CLOUD,
        eu.sovereigntylens.domain.model.Jurisdiction.UNITED_STATES,
        false);
  }

  public static Dependency domainSeedDependency() {
    return new Dependency(
        SEED_EDGE_ROOT_TO_ALPINE_ID,
        ROOT_ORGANIZATION_ID,
        ALPINE_ID,
        true,
        eu.sovereigntylens.domain.model.DependencyStatus.ACTIVE,
        SEED_CREATED_AT);
  }

  public static Dependency domainContributedDependency() {
    return new Dependency(
        CONTRIBUTED_EDGE_ID,
        BALTIC_ID,
        CONTRIBUTED_NODE_ID,
        false,
        eu.sovereigntylens.domain.model.DependencyStatus.ACTIVE,
        CONTRIBUTION_CREATED_AT);
  }

  /**
   * A view that honours the {@link GraphView} invariant: every edge endpoint is among the nodes.
   * The Baltic node is present without an edge of its own, standing in for the session root, which
   * a snapshot always carries whether or not an edge reaches it.
   */
  public static GraphView domainGraphView() {
    return new GraphView(
        domainSession(),
        List.of(domainRootOrganization(), domainAlpineOrganization(), domainBalticOrganization()),
        List.of(domainSeedDependency()));
  }

  public static ContributionCommand contributionCommand() {
    return new ContributionCommand(
        SESSION_SLUG,
        ANONYMOUS_CLIENT_ID,
        BALTIC_ID,
        CONTRIBUTED_NAME,
        eu.sovereigntylens.domain.model.OrganizationType.CLOUD,
        eu.sovereigntylens.domain.model.Jurisdiction.UNITED_STATES);
  }

  public static SubmissionResult submissionResult() {
    return new SubmissionResult(
        DEPENDENCY_CREATED_EVENT_ID,
        CURRENT_ROUND,
        domainContributedOrganization(),
        domainContributedDependency());
  }
}
