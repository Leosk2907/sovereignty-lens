package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.port.SessionRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live event stream for one session.
 *
 * <p>Framing follows the transport amendment exactly: {@code id:} is the event's {@code eventId},
 * {@code event:} is {@code dependency.created} or {@code graph.invalidated}, and {@code data:} is
 * the canonical JSON object as committed. The stream is per session, not per round, so a consumer
 * must still discard events whose {@code round} is not the one it is displaying.
 *
 * <p>The stream is an acceleration hint. {@code GET /api/sessions/{slug}/graph} stays authoritative
 * and nothing here may be treated as durable truth.
 */
@RestController
public class GraphEventsController {

  private final SessionRepository sessions;
  private final GraphEventBroadcaster broadcaster;

  public GraphEventsController(SessionRepository sessions, GraphEventBroadcaster broadcaster) {
    this.sessions = sessions;
    this.broadcaster = broadcaster;
  }

  /**
   * Opens a {@code text/event-stream} for the session.
   *
   * @param lastEventId sent automatically by the browser's {@code EventSource} after a dropped
   *     connection; when it names an event the log still knows, everything after it is replayed
   *     before live delivery starts
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  @GetMapping(path = "/api/sessions/{slug}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream(
      @PathVariable String slug,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {

    // Validated before an emitter exists: once the response is committed as an event stream, a 404
    // can no longer be expressed, and the client would sit on a stream that never produces anything.
    sessions.requireBySlug(slug);

    // An unknown or malformed Last-Event-ID is not an error. The log is trimmed by session reset and
    // a projector can return after hours away; starting live is correct because the client fetches
    // a full snapshot on connect anyway, and failing the request would only delay that snapshot.
    Long resumeFrom = broadcaster.resumeSequence(lastEventId).orElse(null);
    SseEmitter emitter = broadcaster.subscribe(slug, resumeFrom);

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.CONNECTION, "keep-alive")
        // Nginx and most CDN edges buffer proxied responses by default, which turns a live stream
        // into a batch that arrives when the buffer fills, or never.
        .header("X-Accel-Buffering", "no")
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(emitter);
  }
}
