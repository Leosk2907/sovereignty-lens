package eu.sovereigntylens.integration;

import static eu.sovereigntylens.support.DatabaseFixtures.contributorHash;
import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.adapter.persistence.JdbcGraphRepository;
import eu.sovereigntylens.application.AdminService;
import eu.sovereigntylens.application.GraphQueryService;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.GraphView;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.port.SessionRepository;
import eu.sovereigntylens.support.AbstractDatabaseTest;
import eu.sovereigntylens.support.DatabaseFixtures.SessionFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The public read path, where visibility is decided entirely in SQL.
 *
 * <p>Assertions are made against {@link JdbcGraphRepository} rather than through {@link
 * GraphQueryService} wherever the rule under test is a visibility rule: the query service drops
 * dangling edges as a safety net, and asserting through it would make the snapshot invariant hold
 * by construction instead of proving that the node and edge predicates agree.
 */
@DisplayName("Public graph visibility")
class GraphRepositoryIT extends AbstractDatabaseTest {

  @Autowired private JdbcGraphRepository graph;

  @Autowired private SessionRepository sessions;

  @Autowired private GraphQueryService graphQuery;

  @Autowired private AdminService admin;

  @Test
  void showsSeedEdgesInEveryRoundButAudienceEdgesOnlyInTheirOwn() {
    SessionFixture fixture = fixtures.seededSession();
    UUID vendor = audienceOrganization(fixture, "Nimbus Cloud Co");
    UUID audienceEdge =
        fixtures.insertAudienceDependency(
            fixture.sessionId(), 1, fixture.supplier(), vendor, contributorHash("a"));

    assertThat(edgeIds(fixture.slug()))
        .containsExactlyInAnyOrderElementsOf(withSeedEdges(fixture, audienceEdge));

    admin.reset(fixture.slug());

    assertThat(edgeIds(fixture.slug()))
        .containsExactlyInAnyOrderElementsOf(fixture.seedDependencyIds())
        .doesNotContain(audienceEdge);
  }

  @Test
  void neverShowsAHiddenEdge() {
    SessionFixture fixture = fixtures.seededSession();
    UUID vendor = audienceOrganization(fixture, "Nimbus Cloud Co");
    UUID audienceEdge =
        fixtures.insertAudienceDependency(
            fixture.sessionId(), 1, fixture.supplier(), vendor, contributorHash("a"));

    fixtures.setDependencyStatus(audienceEdge, "hidden");

    assertThat(edgeIds(fixture.slug()))
        .containsExactlyInAnyOrderElementsOf(fixture.seedDependencyIds());

    // Hiding a seed edge takes it off the projector too, even though no presenter control can.
    fixtures.setDependencyStatus(fixture.rootToCarrier(), "hidden");
    assertThat(edgeIds(fixture.slug())).doesNotContain(fixture.rootToCarrier());
  }

  @Test
  void dropsANodeThatOnlyAHiddenEdgeMadeReachable() {
    SessionFixture fixture = fixtures.seededSession();
    UUID vendor = audienceOrganization(fixture, "Nimbus Cloud Co");
    UUID audienceEdge =
        fixtures.insertAudienceDependency(
            fixture.sessionId(), 1, fixture.supplier(), vendor, contributorHash("a"));

    assertThat(nodeIds(fixture.slug())).contains(vendor);

    fixtures.setDependencyStatus(audienceEdge, "hidden");

    assertThat(nodeIds(fixture.slug()))
        .doesNotContain(vendor)
        // The supplier survives: it is still an endpoint of a seed edge.
        .contains(fixture.root(), fixture.supplier());
  }

  @Test
  void showsTheSessionRootEvenWithNoEdgesAtAll() {
    SessionFixture fixture = fixtures.bareSession();

    assertThat(edgeIds(fixture.slug())).isEmpty();
    assertThat(nodeIds(fixture.slug())).containsExactly(fixture.root());
  }

  @Test
  void neverReturnsAnEdgeWhoseEndpointsAreNotBothVisible() {
    SessionFixture fixture = fixtures.seededSession();
    UUID visible = audienceOrganization(fixture, "Nimbus Cloud Co");
    UUID hiddenOnly = audienceOrganization(fixture, "Ghost Systems");
    UUID previousRoundOnly = audienceOrganization(fixture, "Yesterday Analytics");

    fixtures.insertAudienceDependency(
        fixture.sessionId(), 1, fixture.supplier(), visible, contributorHash("a"));
    UUID hiddenEdge =
        fixtures.insertAudienceDependency(
            fixture.sessionId(), 1, fixture.carrier(), hiddenOnly, contributorHash("b"));
    fixtures.setDependencyStatus(hiddenEdge, "hidden");
    fixtures.insertAudienceDependency(
        fixture.sessionId(), 1, fixture.root(), previousRoundOnly, contributorHash("c"));

    admin.reset(fixture.slug());
    UUID nextRoundVendor = audienceOrganization(fixture, "Tomorrow Logistics");
    fixtures.insertAudienceDependency(
        fixture.sessionId(), 2, fixture.subSupplier(), nextRoundVendor, contributorHash("d"));

    Session session = sessions.requireBySlug(fixture.slug());
    Set<String> visibleNodeIds =
        graph.findVisibleNodes(session).stream()
            .map(Organization::id)
            .collect(Collectors.toSet());

    assertThat(graph.findVisibleEdges(session))
        .isNotEmpty()
        .allSatisfy(
            edge -> {
              assertThat(visibleNodeIds).contains(edge.sourceOrganizationId());
              assertThat(visibleNodeIds).contains(edge.targetOrganizationId());
            });

    // And the query service, which enforces the same invariant defensively, drops nothing.
    GraphView view = graphQuery.load(fixture.slug());
    assertThat(view.edges()).hasSameSizeAs(graph.findVisibleEdges(session));
  }

  /**
   * Two rows share a {@code created_at} on purpose: without a tiebreak the database is free to
   * return them in either order, and a projector that re-lays-out its graph on every poll would
   * jitter.
   */
  @Test
  void ordersByCreationTimeThenIdAndReturnsTheSameOrderOnEveryRead() {
    SessionFixture fixture = fixtures.bareSession();

    // Postgres compares uuid values byte by byte, which for the canonical lowercase form is the
    // same as comparing the strings, so this is the order the id tiebreak has to produce.
    List<UUID> organizationIds = twoIdsInDatabaseOrder();
    List<UUID> dependencyIds = twoIdsInDatabaseOrder();

    // Later than the root so the timestamp, not the id, decides where the root sits.
    Instant sharedInstant = Instant.now().plus(1, ChronoUnit.HOURS);
    for (int i = 0; i < 2; i++) {
      fixtures.insertOrganization(
          organizationIds.get(i),
          fixture.sessionId(),
          "Simultaneous Vendor " + i,
          OrganizationType.CLOUD,
          Jurisdiction.UNITED_STATES,
          false,
          sharedInstant);
      fixtures.insertDependency(
          dependencyIds.get(i),
          fixture.sessionId(),
          1,
          fixture.root(),
          organizationIds.get(i),
          contributorHash("simultaneous-" + i),
          false,
          "active",
          sharedInstant);
    }

    assertThat(nodeIds(fixture.slug()))
        .containsExactly(fixture.root(), organizationIds.get(0), organizationIds.get(1));
    assertThat(edgeIds(fixture.slug())).containsExactlyElementsOf(dependencyIds);

    // Repeating the read must not shuffle anything: this is what "deterministic" has to mean.
    assertThat(edgeIds(fixture.slug())).containsExactlyElementsOf(edgeIds(fixture.slug()));
    assertThat(nodeIds(fixture.slug())).containsExactlyElementsOf(nodeIds(fixture.slug()));
  }

  @Test
  void dropsThePreviousRoundsAudienceEdgesOnResetButKeepsEverySeedEdge() {
    SessionFixture fixture = fixtures.seededSession();
    UUID vendor = audienceOrganization(fixture, "Nimbus Cloud Co");
    UUID audienceEdge =
        fixtures.insertAudienceDependency(
            fixture.sessionId(), 1, fixture.supplier(), vendor, contributorHash("a"));

    admin.reset(fixture.slug());

    assertThat(edgeIds(fixture.slug()))
        .containsExactlyInAnyOrderElementsOf(fixture.seedDependencyIds());
    // Reset changes what is visible, not what exists: the row is still on disk in round one.
    assertThat(fixtures.findDependency(audienceEdge))
        .hasValueSatisfying(
            row -> {
              assertThat(row.round()).isEqualTo(1);
              assertThat(row.status()).isEqualTo("active");
            });
  }

  private UUID audienceOrganization(SessionFixture fixture, String name) {
    return fixtures.insertOrganization(
        fixture.sessionId(), name, OrganizationType.CLOUD, Jurisdiction.UNITED_STATES, false);
  }

  private List<UUID> withSeedEdges(SessionFixture fixture, UUID... extra) {
    return java.util.stream.Stream.concat(
            fixture.seedDependencyIds().stream(), java.util.Arrays.stream(extra))
        .toList();
  }

  private List<UUID> edgeIds(String slug) {
    return graph.findVisibleEdges(sessions.requireBySlug(slug)).stream()
        .map(Dependency::id)
        .map(UUID::fromString)
        .toList();
  }

  private List<UUID> nodeIds(String slug) {
    return graph.findVisibleNodes(sessions.requireBySlug(slug)).stream()
        .map(Organization::id)
        .map(UUID::fromString)
        .toList();
  }

  private static List<UUID> twoIdsInDatabaseOrder() {
    return java.util.stream.Stream.of(UUID.randomUUID(), UUID.randomUUID())
        .sorted(Comparator.comparing(UUID::toString))
        .toList();
  }
}
