package eu.sovereigntylens.contract;

import java.time.Instant;

/**
 * Emitted by admin mutations. Carries no graph records: consumers refetch the authoritative
 * snapshot immediately.
 *
 * <p>On reset the event uses the new current round, so a client leaves the old topic, fetches the
 * snapshot, and subscribes to the new topic.
 */
public record GraphInvalidatedEvent(
    int contractVersion,
    String event,
    String eventId,
    String sessionSlug,
    int round,
    AdminInvalidationReason reason,
    Instant occurredAt)
    implements GraphEvent {

  public static GraphInvalidatedEvent of(
      String eventId,
      String sessionSlug,
      int round,
      AdminInvalidationReason reason,
      Instant occurredAt) {
    return new GraphInvalidatedEvent(
        ContractVersion.CURRENT,
        GraphEvent.GRAPH_INVALIDATED,
        eventId,
        sessionSlug,
        round,
        reason,
        occurredAt);
  }
}
