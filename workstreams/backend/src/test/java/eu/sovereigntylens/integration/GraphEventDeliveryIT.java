package eu.sovereigntylens.integration;

import static eu.sovereigntylens.support.DatabaseFixtures.contributorHash;
import static eu.sovereigntylens.support.DatabaseFixtures.submission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.adapter.persistence.JdbcContributionRepository;
import eu.sovereigntylens.adapter.web.GraphEventBroadcaster;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.support.AbstractDatabaseTest;
import eu.sovereigntylens.support.DatabaseFixtures.SessionFixture;
import eu.sovereigntylens.support.DatabaseFixtures.StoredEvent;
import eu.sovereigntylens.support.SseTestClient;
import eu.sovereigntylens.support.SseTestClient.Frame;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The {@code pg_notify} bridge and the Server-Sent Events fan-out, end to end.
 *
 * <p>Synchronisation is Awaitility (already on the classpath through {@code
 * spring-boot-starter-test}) for "the subscriber is registered", and bounded queue polls inside
 * {@link SseTestClient} for "a frame arrived". Nothing here sleeps for a fixed period and then
 * asserts, which is the usual way an event-delivery test becomes flaky on a loaded CI box.
 *
 * <p>"Nothing was delivered" is never asserted by waiting and hoping: the rollback test causes a
 * <em>second</em>, committed contribution and asserts that the first frame to arrive is that one.
 */
@DisplayName("Live event delivery")
class GraphEventDeliveryIT extends AbstractDatabaseTest {

  private static final int GENEROUS_CAPACITY = 150;

  /** Generous: the listener polls with a ten second timeout and CI boxes are slow. */
  private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(20);

  /**
   * Used only after a frame that must be the last one has already arrived, so this is a short
   * confirmation rather than the test's only synchronisation.
   */
  private static final Duration QUIET_PERIOD = Duration.ofSeconds(2);

  @LocalServerPort private int port;

  @Autowired private GraphEventBroadcaster broadcaster;

  @Autowired private JdbcContributionRepository contributions;

  @Autowired private TestRestTemplate rest;

  @Autowired private ObjectMapper objectMapper;

  private SessionFixture session;
  private SseTestClient stream;

  @BeforeEach
  void createSession() {
    session = fixtures.seededSession();
  }

  @AfterEach
  void closeStream() {
    if (stream != null) {
      stream.close();
      stream = null;
    }
  }

  @Test
  void deliversACommittedContributionWithTheEventIdOfTheStoredRowAndTheResponse() throws Exception {
    int before = broadcaster.subscriberCount();
    stream = openStream(null);
    awaitSubscriberBeyond(before);

    ResponseEntity<String> response =
        postContribution(session.supplier(), "Nimbus Cloud Co", UUID.randomUUID());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode created = objectMapper.readTree(response.getBody());
    String eventId = created.path("eventId").asText();

    Frame frame = nextFrame();
    assertThat(frame.event()).isEqualTo("dependency.created");
    assertThat(frame.id()).isEqualTo(eventId);

    StoredEvent stored = onlyStoredEvent();
    assertThat(stored.eventId()).isEqualTo(eventId);
    // The bytes on the wire are the bytes the writing transaction committed, not a re-serialization.
    assertThat(objectMapper.readTree(frame.data()))
        .isEqualTo(objectMapper.readTree(stored.payload()));

    JsonNode delivered = objectMapper.readTree(frame.data());
    assertThat(delivered.path("contractVersion").asInt()).isEqualTo(1);
    assertThat(delivered.path("sessionSlug").asText()).isEqualTo(session.slug());
    assertThat(delivered.path("round").asInt()).isEqualTo(1);
    assertThat(delivered.path("node").path("id").asText())
        .isEqualTo(created.path("node").path("id").asText());
    assertThat(delivered.path("edge").path("id").asText())
        .isEqualTo(created.path("edge").path("id").asText());
  }

  @Test
  void deliversNothingForARolledBackContribution() throws Exception {
    int before = broadcaster.subscriberCount();
    stream = openStream(null);
    awaitSubscriberBeyond(before);

    Throwable rollback =
        catchThrowable(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      contributions.submit(
                          submission(
                              session.slug(),
                              session.supplier(),
                              "Phantom Systems",
                              OrganizationType.CLOUD,
                              Jurisdiction.UNITED_STATES,
                              contributorHash("rolled-back"),
                              GENEROUS_CAPACITY));
                      throw new IllegalStateException("forced rollback");
                    }));
    assertThat(rollback).isInstanceOf(IllegalStateException.class);

    SubmissionResult committed =
        contributions.submit(
            submission(
                session.slug(),
                session.carrier(),
                "Nimbus Cloud Co",
                OrganizationType.CLOUD,
                Jurisdiction.UNITED_STATES,
                contributorHash("committed"),
                GENEROUS_CAPACITY));

    // pg_notify fires on commit only, so the very first frame on this stream has to be the
    // committed contribution. If the rollback had leaked an event, it would be this one instead.
    assertThat(nextFrame().id()).isEqualTo(committed.eventId());
    assertThat(stream.nextFrame(QUIET_PERIOD)).isEmpty();
    assertThat(fixtures.eventsOf(session.slug()))
        .singleElement()
        .satisfies(event -> assertThat(event.eventId()).isEqualTo(committed.eventId()));
  }

  @Test
  void replaysOnlyTheEventsAfterTheLastEventIdASubscriberResumesFrom() throws Exception {
    SubmissionResult first = contribute(session.root(), "First Vendor", "a");
    SubmissionResult second = contribute(session.supplier(), "Second Vendor", "b");
    SubmissionResult third = contribute(session.carrier(), "Third Vendor", "c");

    stream = openStream(first.eventId());

    List<Frame> replayed = stream.nextFrames(2, DELIVERY_TIMEOUT);
    assertThat(replayed)
        .extracting(Frame::id)
        .containsExactly(second.eventId(), third.eventId());
    assertThat(replayed).extracting(Frame::event).containsOnly("dependency.created");
    // The event the client said it already had must not be sent again.
    assertThat(stream.nextFrame(QUIET_PERIOD)).isEmpty();
  }

  @Test
  void startsLiveWhenTheLastEventIdIsUnknown() throws Exception {
    contribute(session.root(), "Already Seen Vendor", "a");

    int before = broadcaster.subscriberCount();
    // An unknown id is a cache miss, not an error: the client reconciles against the snapshot.
    stream = openStream(UUID.randomUUID().toString());
    awaitSubscriberBeyond(before);

    SubmissionResult afterSubscribing = contribute(session.supplier(), "Fresh Vendor", "b");

    assertThat(nextFrame().id()).isEqualTo(afterSubscribing.eventId());
  }

  private SubmissionResult contribute(UUID sourceId, String targetName, String browser) {
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

  private ResponseEntity<String> postContribution(UUID sourceId, String name, UUID clientId) {
    String body =
        """
        {"contractVersion":1,"anonymousClientId":"%s","sourceOrganizationId":"%s",\
        "target":{"name":"%s","organizationType":"cloud","jurisdiction":"united_states"}}"""
            .formatted(clientId, sourceId, name);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/api/sessions/{slug}/dependencies",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class,
        session.slug());
  }

  private SseTestClient openStream(String lastEventId) throws Exception {
    URI uri =
        URI.create("http://localhost:" + port + "/api/sessions/" + session.slug() + "/events");
    return SseTestClient.connect(uri, lastEventId, DELIVERY_TIMEOUT);
  }

  /**
   * Waits until the stream is registered with the broadcaster. Causing an event before that point
   * would race the subscription and lose the notification for reasons that have nothing to do with
   * the behaviour under test.
   */
  private void awaitSubscriberBeyond(int previousCount) {
    Awaitility.await()
        .atMost(DELIVERY_TIMEOUT)
        .pollInterval(Duration.ofMillis(25))
        .until(() -> broadcaster.subscriberCount() > previousCount);
  }

  private Frame nextFrame() throws InterruptedException {
    return stream
        .nextFrame(DELIVERY_TIMEOUT)
        .orElseThrow(() -> new AssertionError("No event was delivered within " + DELIVERY_TIMEOUT));
  }

  private StoredEvent onlyStoredEvent() {
    List<StoredEvent> events = fixtures.eventsOf(session.slug());
    assertThat(events).hasSize(1);
    return events.get(0);
  }
}
