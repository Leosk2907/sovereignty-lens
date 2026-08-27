package eu.sovereigntylens.contract;

import java.time.Instant;

/**
 * Emitted exactly once for a committed contribution, never for a rolled-back one.
 *
 * <p>Carries the same canonical node and edge as the HTTP success response.
 */
public record DependencyCreatedEvent(
    int contractVersion,
    String event,
    String eventId,
    String sessionSlug,
    int round,
    GraphNode node,
    GraphEdge edge,
    Instant occurredAt)
    implements GraphEvent {

  public static DependencyCreatedEvent of(
      String eventId,
      String sessionSlug,
      int round,
      GraphNode node,
      GraphEdge edge,
      Instant occurredAt) {
    return new DependencyCreatedEvent(
        ContractVersion.CURRENT,
        GraphEvent.DEPENDENCY_CREATED,
        eventId,
        sessionSlug,
        round,
        node,
        edge,
        occurredAt);
  }
}
