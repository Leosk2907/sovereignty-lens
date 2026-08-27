package eu.sovereigntylens.integration;

import static eu.sovereigntylens.support.DatabaseFixtures.contributorHash;
import static eu.sovereigntylens.support.DatabaseFixtures.submission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.adapter.persistence.JdbcContributionRepository;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.support.DatabaseFixtures.DependencyRow;
import eu.sovereigntylens.support.DatabaseFixtures.OrganizationRow;
import eu.sovereigntylens.support.DatabaseFixtures.SessionFixture;
import eu.sovereigntylens.support.DatabaseFixtures.StoredEvent;
import eu.sovereigntylens.support.AbstractDatabaseTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@code submit_dependency} SQL function, which is where every contribution invariant is
 * actually enforced.
 *
 * <p>These tests go through {@link JdbcContributionRepository} rather than through {@code
 * ContributionService}, so the function's own checks are exercised instead of the use case's
 * pre-checks: the database is the only layer that can evaluate them atomically and is therefore the
 * only one worth proving.
 */
@DisplayName("submit_dependency")
class SubmitDependencyFunctionIT extends AbstractDatabaseTest {

  /** High enough that only the capacity tests, which pass their own limit, ever reach it. */
  private static final int GENEROUS_CAPACITY = 150;

  /** Enough to interleave without exhausting the twelve-connection pool the listener shares. */
  private static final int RACERS = 6;

  @Autowired private JdbcContributionRepository contributions;

  @Autowired private ObjectMapper objectMapper;

  private SessionFixture session;

  @BeforeEach
  void createSession() {
    session = fixtures.seededSession();
  }

  @Test
  void storesOneOrganizationOneDependencyAndExactlyOneEvent() {
    SubmissionResult result =
        contributions.submit(
            submission(
                session.slug(),
                session.supplier(),
                "Nimbus Cloud Co",
                OrganizationType.CLOUD,
                Jurisdiction.UNITED_STATES,
                contributorHash("a"),
                GENEROUS_CAPACITY));

    assertThat(result.round()).isEqualTo(1);
    assertThat(result.targetNode().name()).isEqualTo("Nimbus Cloud Co");
    assertThat(result.targetNode().organizationType()).isEqualTo(OrganizationType.CLOUD);
    assertThat(result.targetNode().jurisdiction()).isEqualTo(Jurisdiction.UNITED_STATES);
    assertThat(result.targetNode().seed()).isFalse();
    assertThat(result.edge().sourceOrganizationId()).isEqualTo(session.supplier().toString());
    assertThat(result.edge().targetOrganizationId()).isEqualTo(result.targetNode().id());
    assertThat(result.edge().status()).isEqualTo(DependencyStatus.ACTIVE);
    assertThat(result.edge().seed()).isFalse();

    List<OrganizationRow> audienceOrganizations =
        fixtures.organizationsOf(session.sessionId()).stream().filter(row -> !row.seed()).toList();
    assertThat(audienceOrganizations)
        .singleElement()
        .satisfies(row -> assertThat(row.id().toString()).isEqualTo(result.targetNode().id()));

    List<DependencyRow> audienceEdges = audienceDependencies();
    assertThat(audienceEdges)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id().toString()).isEqualTo(result.edge().id());
              assertThat(row.round()).isEqualTo(1);
              assertThat(row.status()).isEqualTo("active");
            });

    assertThat(fixtures.eventsOf(session.slug())).hasSize(1);
  }

  @Test
  void emitsACanonicalDependencyCreatedEventMatchingTheStoredRows() throws Exception {
    SubmissionResult result =
        contributions.submit(
            submission(
                session.slug(),
                session.root(),
                "Meridian Analytics",
                OrganizationType.SOFTWARE,
                Jurisdiction.CHINA,
                contributorHash("a"),
                GENEROUS_CAPACITY));

    StoredEvent stored = onlyEvent();
    assertThat(stored.eventType()).isEqualTo("dependency.created");
    assertThat(stored.round()).isEqualTo(1);
    assertThat(stored.eventId()).isEqualTo(result.eventId());

    JsonNode payload = objectMapper.readTree(stored.payload());
    assertThat(payload.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(payload.path("event").asText()).isEqualTo("dependency.created");
    assertThat(payload.path("eventId").asText()).isEqualTo(result.eventId());
    assertThat(payload.path("sessionSlug").asText()).isEqualTo(session.slug());
    assertThat(payload.path("round").asInt()).isEqualTo(1);
    assertThat(payload.path("occurredAt").asText()).isNotBlank();
    assertThat(payload.path("node").path("id").asText()).isEqualTo(result.targetNode().id());
    assertThat(payload.path("node").path("name").asText()).isEqualTo("Meridian Analytics");
    assertThat(payload.path("node").path("organizationType").asText()).isEqualTo("software");
    assertThat(payload.path("node").path("jurisdiction").asText()).isEqualTo("china");
    assertThat(payload.path("node").path("isSeed").asBoolean()).isFalse();
    assertThat(payload.path("edge").path("id").asText()).isEqualTo(result.edge().id());
    assertThat(payload.path("edge").path("sourceOrganizationId").asText())
        .isEqualTo(session.root().toString());
    assertThat(payload.path("edge").path("targetOrganizationId").asText())
        .isEqualTo(result.targetNode().id());
    assertThat(payload.path("edge").path("status").asText()).isEqualTo("active");
    assertThat(payload.path("edge").path("isSeed").asBoolean()).isFalse();
  }

  /**
   * The single most important guarantee in the system: the dependency row and the event row are one
   * fact. A rolled-back contribution that still left an event behind would put a node on the
   * projector that no snapshot can ever confirm.
   */
  @Test
  void rollingBackTheSurroundingTransactionLeavesNeitherARowNorAnEvent() {
    List<SubmissionResult> inFlight = new ArrayList<>();

    Throwable rollback =
        catchThrowable(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      inFlight.add(
                          contributions.submit(
                              submission(
                                  session.slug(),
                                  session.supplier(),
                                  "Phantom Systems",
                                  OrganizationType.CLOUD,
                                  Jurisdiction.UNITED_STATES,
                                  contributorHash("a"),
                                  GENEROUS_CAPACITY)));
                      throw new IllegalStateException("forced rollback");
                    }));

    assertThat(rollback).isInstanceOf(IllegalStateException.class);
    // The call really did succeed before the rollback, so this is a rollback test rather than a
    // test that the submission failed for some unrelated reason.
    assertThat(inFlight).hasSize(1);

    assertThat(audienceDependencies()).isEmpty();
    assertThat(fixtures.findDependency(UUID.fromString(inFlight.get(0).edge().id()))).isEmpty();
    assertThat(fixtures.eventsOf(session.slug())).isEmpty();
    assertThat(fixtures.organizationsOf(session.sessionId()))
        .allSatisfy(row -> assertThat(row.seed()).isTrue());
  }

  @Test
  void reusesOneOrganizationWhenTwoSourcesNameTheSameCompany() {
    SubmissionResult first =
        contributions.submit(
            submission(
                session.slug(),
                session.root(),
                "Nimbus Cloud Co",
                OrganizationType.CLOUD,
                Jurisdiction.UNITED_STATES,
                contributorHash("a"),
                GENEROUS_CAPACITY));

    // Same company, typed by a different audience member: different case, stray whitespace, and a
    // different guess at the type and jurisdiction.
    SubmissionResult second =
        contributions.submit(
            submission(
                session.slug(),
                session.supplier(),
                "  nimbus   CLOUD co ",
                OrganizationType.OTHER,
                Jurisdiction.UNKNOWN,
                contributorHash("b"),
                GENEROUS_CAPACITY));

    assertThat(second.targetNode().id()).isEqualTo(first.targetNode().id());
    // Reuse keeps the row that already exists, so the first contributor's spelling and type win.
    assertThat(second.targetNode().name()).isEqualTo("Nimbus Cloud Co");
    assertThat(second.targetNode().organizationType()).isEqualTo(OrganizationType.CLOUD);
    assertThat(second.targetNode().jurisdiction()).isEqualTo(Jurisdiction.UNITED_STATES);

    assertThat(fixtures.organizationsOf(session.sessionId()).stream().filter(row -> !row.seed()))
        .hasSize(1);
    assertThat(audienceDependencies())
        .hasSize(2)
        .allSatisfy(
            row ->
                assertThat(row.targetOrganizationId().toString())
                    .isEqualTo(first.targetNode().id()))
        .extracting(DependencyRow::sourceOrganizationId)
        .containsExactlyInAnyOrder(session.root(), session.supplier());
  }

  @Test
  void rejectsAPausedSession() {
    fixtures.pauseSession(session.slug());

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.supplier(),
                    "Nimbus Cloud Co",
                    OrganizationType.CLOUD,
                    Jurisdiction.UNITED_STATES,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL003",
        DomainErrorCode.SESSION_PAUSED);
  }

  @Test
  void rejectsAnUnknownSession() {
    assertRejected(
        () ->
            contributions.submit(
                submission(
                    "no-such-session-" + UUID.randomUUID(),
                    session.supplier(),
                    "Nimbus Cloud Co",
                    OrganizationType.CLOUD,
                    Jurisdiction.UNITED_STATES,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL001",
        DomainErrorCode.SESSION_NOT_FOUND);
  }

  @Test
  void rejectsASourceThatIsNotPartOfTheSession() {
    // A real organization, but in a different session: the function scopes the source lookup by
    // session, so borrowing another session's node must not work.
    SessionFixture other = fixtures.seededSession();

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    other.supplier(),
                    "Nimbus Cloud Co",
                    OrganizationType.CLOUD,
                    Jurisdiction.UNITED_STATES,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL002",
        DomainErrorCode.SOURCE_NOT_FOUND);
  }

  @Test
  void rejectsASecondSubmissionFromTheSameBrowserInOneRound() {
    String browser = contributorHash("a");
    contributions.submit(
        submission(
            session.slug(),
            session.supplier(),
            "Nimbus Cloud Co",
            OrganizationType.CLOUD,
            Jurisdiction.UNITED_STATES,
            browser,
            GENEROUS_CAPACITY));

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.root(),
                    "Meridian Analytics",
                    OrganizationType.SOFTWARE,
                    Jurisdiction.CHINA,
                    browser,
                    GENEROUS_CAPACITY)),
        "SL004",
        DomainErrorCode.ALREADY_CONTRIBUTED);
  }

  @Test
  void rejectsAnEdgeThatIsAlreadyActiveInTheRound() {
    contributions.submit(
        submission(
            session.slug(),
            session.supplier(),
            "Nimbus Cloud Co",
            OrganizationType.CLOUD,
            Jurisdiction.UNITED_STATES,
            contributorHash("a"),
            GENEROUS_CAPACITY));

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.supplier(),
                    "Nimbus Cloud Co",
                    OrganizationType.CLOUD,
                    Jurisdiction.UNITED_STATES,
                    contributorHash("b"),
                    GENEROUS_CAPACITY)),
        "SL005",
        DomainErrorCode.DUPLICATE_DEPENDENCY);
  }

  /**
   * Seed rows sit in round bucket zero, which the duplicate check reads alongside the current
   * round: an audience member retyping an edge the presenter already seeded must not double it.
   */
  @Test
  void rejectsAnEdgeThatDuplicatesASeedEdge() {
    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.root(),
                    "Alpine Civic Systems",
                    OrganizationType.SOFTWARE,
                    Jurisdiction.EUROPE,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL005",
        DomainErrorCode.DUPLICATE_DEPENDENCY);
  }

  @Test
  void rejectsASubmissionOnceTheRoundIsFull() {
    // A capacity of one instead of a hundred and fifty rows: the rule under test is the comparison,
    // not the number.
    contributions.submit(
        submission(
            session.slug(),
            session.supplier(),
            "Nimbus Cloud Co",
            OrganizationType.CLOUD,
            Jurisdiction.UNITED_STATES,
            contributorHash("a"),
            1));

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.root(),
                    "Meridian Analytics",
                    OrganizationType.SOFTWARE,
                    Jurisdiction.CHINA,
                    contributorHash("b"),
                    1)),
        "SL006",
        DomainErrorCode.ROUND_CAPACITY_REACHED);
  }

  @Test
  void rejectsAnOrganizationDependingOnItself() {
    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.supplier(),
                    "Alpine Civic Systems",
                    OrganizationType.SOFTWARE,
                    Jurisdiction.EUROPE,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL007",
        DomainErrorCode.VALIDATION_ERROR);
  }

  @Test
  void rejectsAGovernmentTarget() {
    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.supplier(),
                    "Ministry Of Everything",
                    OrganizationType.GOVERNMENT,
                    Jurisdiction.EUROPE,
                    contributorHash("a"),
                    GENEROUS_CAPACITY)),
        "SL007",
        DomainErrorCode.VALIDATION_ERROR);
  }

  /**
   * Capacity counts active non-seed rows, so hiding spam gives the round its slot back. The
   * contributor index is deliberately not filtered by status, so it does not also give that browser
   * another turn.
   */
  @Test
  void hidingARowFreesACapacitySlotButNotTheBrowsersTurn() {
    String browser = contributorHash("a");
    SubmissionResult first =
        contributions.submit(
            submission(
                session.slug(),
                session.supplier(),
                "Nimbus Cloud Co",
                OrganizationType.CLOUD,
                Jurisdiction.UNITED_STATES,
                browser,
                1));

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.root(),
                    "Meridian Analytics",
                    OrganizationType.SOFTWARE,
                    Jurisdiction.CHINA,
                    contributorHash("b"),
                    1)),
        "SL006",
        DomainErrorCode.ROUND_CAPACITY_REACHED);

    fixtures.setDependencyStatus(UUID.fromString(first.edge().id()), "hidden");

    SubmissionResult afterHiding =
        contributions.submit(
            submission(
                session.slug(),
                session.root(),
                "Meridian Analytics",
                OrganizationType.SOFTWARE,
                Jurisdiction.CHINA,
                contributorHash("b"),
                1));
    assertThat(afterHiding.round()).isEqualTo(1);

    assertRejected(
        () ->
            contributions.submit(
                submission(
                    session.slug(),
                    session.carrier(),
                    "Harbour Freight Lines",
                    OrganizationType.LOGISTICS,
                    Jurisdiction.OTHER_EXTERNAL,
                    browser,
                    GENEROUS_CAPACITY)),
        "SL004",
        DomainErrorCode.ALREADY_CONTRIBUTED);
  }

  @Test
  void letsExactlyOneOfManySimultaneousSubmissionsFromOneBrowserThrough() throws Exception {
    String browser = contributorHash("shared-browser");
    List<DependencySubmission> submissions = new ArrayList<>();
    for (int i = 0; i < RACERS; i++) {
      submissions.add(
          submission(
              session.slug(),
              session.supplier(),
              "Racer Company " + i,
              OrganizationType.CLOUD,
              Jurisdiction.UNITED_STATES,
              browser,
              GENEROUS_CAPACITY));
    }

    List<Outcome> outcomes = race(submissions);

    assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.succeeded())
        .hasSize(RACERS - 1)
        .allSatisfy(
            outcome ->
                assertThat(outcome.code()).isEqualTo(DomainErrorCode.ALREADY_CONTRIBUTED));
    assertThat(audienceDependencies()).hasSize(1);
    assertThat(fixtures.eventsOf(session.slug())).hasSize(1);
  }

  @Test
  void letsExactlyOneOfManySimultaneousSubmissionsOfTheSameEdgeThrough() throws Exception {
    List<DependencySubmission> submissions = new ArrayList<>();
    for (int i = 0; i < RACERS; i++) {
      submissions.add(
          submission(
              session.slug(),
              session.supplier(),
              "Contested Company",
              OrganizationType.CLOUD,
              Jurisdiction.UNITED_STATES,
              contributorHash("browser-" + i),
              GENEROUS_CAPACITY));
    }

    List<Outcome> outcomes = race(submissions);

    assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.succeeded())
        .hasSize(RACERS - 1)
        .allSatisfy(
            outcome ->
                assertThat(outcome.code()).isEqualTo(DomainErrorCode.DUPLICATE_DEPENDENCY));
    assertThat(audienceDependencies()).hasSize(1);
    // The losing calls must not have left half-built organizations behind either.
    assertThat(fixtures.organizationsOf(session.sessionId()).stream().filter(row -> !row.seed()))
        .hasSize(1);
    assertThat(fixtures.eventsOf(session.slug())).hasSize(1);
  }

  /**
   * Runs every submission at once and reports what each one got.
   *
   * <p>A latch rather than "submit them all and hope": without it the pool would happily run the
   * first task to completion before the last one is even scheduled, and the test would pass without
   * ever having produced a race.
   */
  private List<Outcome> race(List<DependencySubmission> submissions) throws Exception {
    int workers = submissions.size();
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Outcome>> futures = new ArrayList<>(workers);
      for (DependencySubmission submission : submissions) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    contributions.submit(submission);
                    return Outcome.won();
                  } catch (DomainException e) {
                    return Outcome.lost(e.code());
                  }
                }));
      }

      assertThat(ready.await(30, TimeUnit.SECONDS))
          .describedAs("all workers reached the starting gate")
          .isTrue();
      start.countDown();

      List<Outcome> outcomes = new ArrayList<>(workers);
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get(60, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Asserts that a call failed with the documented SQLSTATE <em>and</em> the domain code it maps to.
   * Checking only the domain code would let the two drift apart silently.
   */
  private void assertRejected(ThrowingCallable call, String sqlState, DomainErrorCode code) {
    Throwable thrown = catchThrowable(call);

    assertThat(thrown).isInstanceOf(DomainException.class);
    assertThat(((DomainException) thrown).code()).isEqualTo(code);
    assertThat(sqlStateOf(thrown))
        .describedAs("SQLSTATE raised by submit_dependency")
        .isEqualTo(sqlState);
  }

  private static String sqlStateOf(Throwable thrown) {
    for (Throwable current = thrown; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        for (SQLException link = sqlException; link != null; link = link.getNextException()) {
          if (link.getSQLState() != null && link.getSQLState().startsWith("SL")) {
            return link.getSQLState();
          }
        }
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return null;
  }

  private List<DependencyRow> audienceDependencies() {
    return fixtures.dependenciesOf(session.sessionId()).stream().filter(row -> !row.seed()).toList();
  }

  private StoredEvent onlyEvent() {
    List<StoredEvent> events = fixtures.eventsOf(session.slug());
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  private record Outcome(boolean succeeded, DomainErrorCode code) {

    static Outcome won() {
      return new Outcome(true, null);
    }

    static Outcome lost(DomainErrorCode code) {
      return new Outcome(false, code);
    }
  }
}
