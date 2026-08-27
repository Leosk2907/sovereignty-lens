package eu.sovereigntylens.domain.model;

import java.time.Instant;

/**
 * One durable row of the live event log, as committed by the database.
 *
 * <p>The payload stays a raw JSON string on purpose. It was serialized canonically by {@code
 * emit_graph_event} inside the writing transaction, and the contract requires the browser to receive
 * exactly what was committed; parsing it into a contract record and re-serializing it through
 * Jackson would let field order, number formatting or timestamp precision drift away from the bytes
 * the database chose. Nothing on the read path needs to look inside the payload, so nothing does.
 *
 * @param eventId the event's own identifier, echoed as the SSE {@code id:} field
 * @param sequence monotonic-at-insert ordering key used for resume
 * @param eventType {@code dependency.created} or {@code graph.invalidated}, the SSE {@code event:}
 *     field
 * @param payloadJson the canonical contract JSON, one line, byte-for-byte as stored
 */
public record StoredGraphEvent(
    String eventId,
    long sequence,
    String sessionSlug,
    int round,
    String eventType,
    String payloadJson,
    Instant occurredAt) {}
