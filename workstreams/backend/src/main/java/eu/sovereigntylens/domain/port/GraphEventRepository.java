package eu.sovereigntylens.domain.port;

import eu.sovereigntylens.domain.model.StoredGraphEvent;
import java.util.List;
import java.util.Optional;

/**
 * Reads the durable live event log.
 *
 * <p>This port is read-only: events are written by the same database function that writes the data
 * they describe, so that a rolled-back contribution can never produce an event. The read side exists
 * to turn a {@code pg_notify} wake-up into a payload and to let a reconnecting client resume.
 */
public interface GraphEventRepository {

  /** The event a {@code pg_notify} payload names, or empty if the id is unknown or malformed. */
  Optional<StoredGraphEvent> findByEventId(String eventId);

  /**
   * Events of one session ordered by sequence, starting immediately after {@code sequence}.
   *
   * <p>The sequence comes from a {@code bigserial}, which is assigned at insert rather than at
   * commit. Under concurrency a lower sequence can therefore commit after a higher one, so a
   * resume that starts from the highest sequence already delivered can in principle skip an event
   * that committed late. That is tolerated rather than fixed here because every consumer reconciles
   * against {@code GET /api/sessions/{slug}/graph}, which is authoritative; a missed event costs one
   * reconciliation, not a wrong graph. Do not turn this ordering into a correctness assumption.
   *
   * @param limit hard upper bound on returned rows, applied by the query
   */
  List<StoredGraphEvent> findAfterSequence(String sessionSlug, long sequence, int limit);

  /** The sequence of one event, used to translate a {@code Last-Event-ID} header into a position. */
  Optional<Long> findSequenceByEventId(String eventId);

  /** Highest sequence recorded for a session, or {@code 0} when it has no events yet. */
  long latestSequence(String sessionSlug);
}
