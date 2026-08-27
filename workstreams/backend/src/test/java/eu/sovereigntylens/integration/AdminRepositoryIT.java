package eu.sovereigntylens.integration;

import static eu.sovereigntylens.support.DatabaseFixtures.contributorHash;
import static eu.sovereigntylens.support.DatabaseFixtures.submission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.adapter.persistence.JdbcAdminRepository;
import eu.sovereigntylens.adapter.persistence.JdbcContributionRepository;
import eu.sovereigntylens.application.AdminService;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.AdminDependencyView;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SessionStatus;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.support.AbstractDatabaseTest;
import eu.sovereigntylens.support.DatabaseFixtures.DependencyRow;
import eu.sovereigntylens.support.DatabaseFixtures.SessionFixture;
import eu.sovereigntylens.support.DatabaseFixtures.StoredEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The presenter's recovery controls.
 *
 * <p>Two properties matter for every action and are asserted for every action: the change and its
 * {@code graph.invalidated} announcement are one transaction, and no action deletes anything.
 */
@DisplayName("Presenter actions")
class AdminRepositoryIT extends AbstractDatabaseTest {

  private static final int GENEROUS_CAPACITY = 150;

  @Autowired private JdbcAdminRepository adminRepository;

  @Autowired private AdminService admin;

  @Autowired private JdbcContributionRepository contributions;

  @Autowired private ObjectMapper objectMapper;

  private SessionFixture session;

  @BeforeEach
  void createSession() {
    session = fixtures.seededSession();
  }

  @Test
  void pauseClosesTheSessionAndEmitsExactlyOneInvalidation() {
    AdminOutcome outcome = admin.pause(session.slug());

    assertThat(fixtures.sessionStatus(session.slug())).isEqualTo("paused");
    assertThat(outcome.session().status()).isEqualTo(SessionStatus.PAUSED);
    assertOnlyInvalidation(outcome.eventId(), "pause", 1);
  }

  @Test
  void resumeReopensTheSessionAndEmitsExactlyOneInvalidation() {
    // Paused directly rather than through pause(), so this test counts only its own event.
    fixtures.pauseSession(session.slug());

    AdminOutcome outcome = admin.resume(session.slug());

    assertThat(fixtures.sessionStatus(session.slug())).isEqualTo("open");
    assertThat(outcome.session().status()).isEqualTo(SessionStatus.OPEN);
    assertOnlyInvalidation(outcome.eventId(), "resume", 1);
  }

  @Test
  void resetStartsTheNextRoundReopensAPausedSessionAndDeletesNothing() {
    contribute("Nimbus Cloud Co", session.supplier(), "a");
    List<DependencyRow> before = fixtures.dependenciesOf(session.sessionId());
    int organizationsBefore = fixtures.organizationsOf(session.sessionId()).size();
    fixtures.pauseSession(session.slug());

    AdminOutcome outcome = admin.reset(session.slug());

    assertThat(fixtures.currentRound(session.slug())).isEqualTo(2);
    assertThat(fixtures.sessionStatus(session.slug())).isEqualTo("open");
    assertThat(outcome.session().currentRound()).isEqualTo(2);
    assertThat(outcome.session().status()).isEqualTo(SessionStatus.OPEN);

    // The audience is being moved into round two, so that is the round the announcement carries.
    assertOnlyInvalidation(outcome.eventId(), "reset", 2, "dependency.created");

    assertThat(fixtures.dependenciesOf(session.sessionId()))
        .describedAs("reset must move the round marker, not remove rows")
        .containsExactlyInAnyOrderElementsOf(before);
    assertThat(fixtures.organizationsOf(session.sessionId())).hasSize(organizationsBefore);
  }

  @Test
  void undoHidesTheNewestActiveAudienceRowOfTheCurrentRound() {
    SubmissionResult older = contribute("Older Vendor", session.supplier(), "a");
    SubmissionResult newer = contribute("Newer Vendor", session.carrier(), "b");
    // Explicit timestamps rather than whatever two consecutive statements happened to get, so
    // "newest" is a fact of the fixture instead of a race against the clock's resolution.
    orderInTime(older, newer);

    AdminOutcome outcome = admin.undo(session.slug());

    assertThat(statusOf(newer)).isEqualTo("hidden");
    assertThat(statusOf(older)).isEqualTo("active");
    assertOnlyInvalidation(
        outcome.eventId(), "undo", 1, "dependency.created", "dependency.created");
  }

  /**
   * With nothing left to undo the store writes nothing and announces nothing; the use case still
   * announces, because an invalidation is only ever a hint to refetch. Both halves are asserted
   * because the honest empty result is what keeps the two distinguishable.
   */
  @Test
  void undoWithNothingToUndoWritesNothingAtTheStoreButTheUseCaseStillInvalidates() {
    assertThat(adminRepository.undo(session.slug())).isEmpty();
    assertThat(fixtures.eventsOf(session.slug())).isEmpty();

    AdminOutcome outcome = admin.undo(session.slug());

    assertThat(outcome.session().currentRound()).isEqualTo(1);
    assertOnlyInvalidation(outcome.eventId(), "undo", 1);
    assertThat(fixtures.dependenciesOf(session.sessionId()))
        .allSatisfy(row -> assertThat(row.status()).isEqualTo("active"));
  }

  @Test
  void hidingADependencyEmitsExactlyOneInvalidationWithReasonHide() {
    SubmissionResult contribution = contribute("Nimbus Cloud Co", session.supplier(), "a");

    var outcome =
        admin.setDependencyStatus(edgeId(contribution), DependencyStatus.HIDDEN);

    assertThat(outcome.dependency().status()).isEqualTo(DependencyStatus.HIDDEN);
    assertThat(statusOf(contribution)).isEqualTo("hidden");
    assertOnlyInvalidation(outcome.eventId(), "hide", 1, "dependency.created");
  }

  @Test
  void restoringADependencyEmitsExactlyOneInvalidationWithReasonRestore() {
    SubmissionResult contribution = contribute("Nimbus Cloud Co", session.supplier(), "a");
    fixtures.setDependencyStatus(edgeId(contribution), "hidden");

    var outcome = admin.setDependencyStatus(edgeId(contribution), DependencyStatus.ACTIVE);

    assertThat(outcome.dependency().status()).isEqualTo(DependencyStatus.ACTIVE);
    assertThat(statusOf(contribution)).isEqualTo("active");
    assertOnlyInvalidation(outcome.eventId(), "restore", 1, "dependency.created");
  }

  @Test
  void rollsTheChangeAndItsInvalidationBackTogether() {
    Throwable rollback =
        catchThrowable(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      admin.pause(session.slug());
                      throw new IllegalStateException("forced rollback");
                    }));

    assertThat(rollback).isInstanceOf(IllegalStateException.class);
    assertThat(fixtures.sessionStatus(session.slug())).isEqualTo("open");
    assertThat(fixtures.eventsOf(session.slug())).isEmpty();
  }

  @Test
  void refusesToRestoreAnEdgeThatIsAlreadyActiveInTheRound() {
    SubmissionResult first = contribute("Nimbus Cloud Co", session.supplier(), "a");
    admin.setDependencyStatus(edgeId(first), DependencyStatus.HIDDEN);
    // Allowed only because the first row is hidden: the active-edge index ignores hidden rows.
    SubmissionResult second = contribute("Nimbus Cloud Co", session.supplier(), "b");
    assertThat(second.edge().id()).isNotEqualTo(first.edge().id());

    assertFails(
        () -> admin.setDependencyStatus(edgeId(first), DependencyStatus.ACTIVE),
        DomainErrorCode.DUPLICATE_DEPENDENCY);

    assertThat(statusOf(first)).isEqualTo("hidden");
  }

  @Test
  void refusesToHideASeedRow() {
    assertFails(
        () -> admin.setDependencyStatus(session.rootToSupplier(), DependencyStatus.HIDDEN),
        DomainErrorCode.NOT_FOUND);

    assertThat(fixtures.findDependency(session.rootToSupplier()))
        .hasValueSatisfying(row -> assertThat(row.status()).isEqualTo("active"));
  }

  @Test
  void refusesToRestoreASeedRow() {
    fixtures.setDependencyStatus(session.rootToSupplier(), "hidden");

    assertFails(
        () -> admin.setDependencyStatus(session.rootToSupplier(), DependencyStatus.ACTIVE),
        DomainErrorCode.NOT_FOUND);
  }

  @Test
  void refusesToTouchARowFromAPreviousRound() {
    SubmissionResult contribution = contribute("Nimbus Cloud Co", session.supplier(), "a");
    admin.reset(session.slug());

    assertFails(
        () -> admin.setDependencyStatus(edgeId(contribution), DependencyStatus.HIDDEN),
        DomainErrorCode.NOT_FOUND);

    assertThat(statusOf(contribution)).isEqualTo("active");
  }

  @Test
  void refusesToTouchADependencyThatDoesNotExist() {
    assertFails(
        () -> admin.setDependencyStatus(UUID.randomUUID(), DependencyStatus.HIDDEN),
        DomainErrorCode.NOT_FOUND);
  }

  @Test
  void listsCurrentRoundDependenciesNewestFirstIncludingHiddenRows() {
    SubmissionResult first = contribute("First Vendor", session.supplier(), "a");
    SubmissionResult second = contribute("Second Vendor", session.carrier(), "b");
    SubmissionResult third = contribute("Third Vendor", session.subSupplier(), "c");
    orderInTime(first, second, third);
    fixtures.setDependencyStatus(edgeId(second), "hidden");

    AdminService.DependencyListing listing = admin.listDependencies(session.slug());

    assertThat(listing.session().currentRound()).isEqualTo(1);
    assertThat(listing.dependencies())
        .extracting(view -> view.edge().id())
        .containsExactly(third.edge().id(), second.edge().id(), first.edge().id());
    assertThat(listing.dependencies())
        .extracting(view -> view.edge().status())
        .containsExactly(
            DependencyStatus.ACTIVE, DependencyStatus.HIDDEN, DependencyStatus.ACTIVE);
    // Seed rows are the presenter's own scaffolding and never appear in the review list.
    assertThat(listing.dependencies()).allSatisfy(view -> assertThat(view.edge().seed()).isFalse());

    AdminDependencyView newest = listing.dependencies().get(0);
    assertThat(newest.source().id()).isEqualTo(session.subSupplier().toString());
    assertThat(newest.target().name()).isEqualTo("Third Vendor");
  }

  private SubmissionResult contribute(String targetName, UUID sourceId, String browser) {
    return contributions.submit(
        submission(
            session.slug(),
            sourceId,
            targetName,
            OrganizationType.CLOUD,
            Jurisdiction.UNITED_STATES,
            contributorHash(browser),
            GENEROUS_CAPACITY));
  }

  /** Spaces the given contributions one minute apart in the order they are passed. */
  private void orderInTime(SubmissionResult... ordered) {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    for (int i = 0; i < ordered.length; i++) {
      fixtures.setDependencyCreatedAt(edgeId(ordered[i]), base.plusSeconds(60L * i));
    }
  }

  private static UUID edgeId(SubmissionResult result) {
    return UUID.fromString(result.edge().id());
  }

  private String statusOf(SubmissionResult result) {
    return fixtures
        .findDependency(edgeId(result))
        .orElseThrow(() -> new AssertionError("dependency vanished"))
        .status();
  }

  /**
   * Asserts the session's event log holds exactly one {@code graph.invalidated}, carrying the given
   * reason and round, alongside only the event types listed as already expected.
   */
  private void assertOnlyInvalidation(
      String eventId, String reason, int round, String... alsoExpected) {
    List<StoredEvent> events = fixtures.eventsOf(session.slug());
    assertThat(events)
        .extracting(StoredEvent::eventType)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(
                    java.util.Arrays.stream(alsoExpected),
                    java.util.stream.Stream.of("graph.invalidated"))
                .toList());

    StoredEvent invalidation =
        events.stream()
            .filter(event -> "graph.invalidated".equals(event.eventType()))
            .findFirst()
            .orElseThrow();
    assertThat(invalidation.eventId()).isEqualTo(eventId);
    assertThat(invalidation.round()).isEqualTo(round);
    assertThat(payload(invalidation).path("reason").asText()).isEqualTo(reason);
    assertThat(payload(invalidation).path("contractVersion").asInt()).isEqualTo(1);
    assertThat(payload(invalidation).path("sessionSlug").asText()).isEqualTo(session.slug());
    assertThat(payload(invalidation).path("event").asText()).isEqualTo("graph.invalidated");
  }

  private com.fasterxml.jackson.databind.JsonNode payload(StoredEvent event) {
    try {
      return objectMapper.readTree(event.payload());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError("Stored event payload is not JSON", e);
    }
  }

  private static void assertFails(ThrowingCallable call, DomainErrorCode code) {
    Throwable thrown = catchThrowable(call);
    assertThat(thrown).isInstanceOf(DomainException.class);
    assertThat(((DomainException) thrown).code()).isEqualTo(code);
  }
}
