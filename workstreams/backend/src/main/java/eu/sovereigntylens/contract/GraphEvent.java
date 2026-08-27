package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/**
 * A live graph event.
 *
 * <p>Events are an acceleration hint only. The database snapshot returned by
 * {@code GET /api/sessions/{slug}/graph} is always authoritative; a consumer that sees an
 * unsupported version, a malformed payload, or an event for another session or round discards it
 * and refetches.
 *
 * <p>Transport: Server-Sent Events on {@code GET /api/sessions/{slug}/events}. See
 * contracts/transport-amendment.md; the payload shapes are unchanged from contract version 1.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "event",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = DependencyCreatedEvent.class, name = GraphEvent.DEPENDENCY_CREATED),
  @JsonSubTypes.Type(value = GraphInvalidatedEvent.class, name = GraphEvent.GRAPH_INVALIDATED)
})
public sealed interface GraphEvent permits DependencyCreatedEvent, GraphInvalidatedEvent {

  String DEPENDENCY_CREATED = "dependency.created";
  String GRAPH_INVALIDATED = "graph.invalidated";

  int contractVersion();

  String event();

  String eventId();

  String sessionSlug();

  int round();

  Instant occurredAt();

  /** The channel a consumer subscribes to, kept identical to the original contract topic name. */
  default String topic() {
    return topicFor(sessionSlug(), round());
  }

  static String topicFor(String sessionSlug, int round) {
    return "sovereignty:" + sessionSlug + ":round:" + round;
  }
}
