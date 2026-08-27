package eu.sovereigntylens.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A minimal Server-Sent Events reader for the integration suite.
 *
 * <p>{@code TestRestTemplate} reads a whole response body before returning, which is exactly what a
 * stream that never ends cannot do. This client keeps the body's line stream on its own daemon
 * thread and hands parsed frames to the test through a queue, so every wait in a test is a bounded
 * {@code poll} with an explicit timeout rather than a sleep.
 *
 * <p>Only what this suite asserts on is parsed: {@code id}, {@code event} and {@code data}.
 * Heartbeat comments and the retry field are discarded.
 */
public final class SseTestClient implements AutoCloseable {

  private final HttpClient http;
  private final ExecutorService reader;
  private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();

  private SseTestClient(HttpClient http, ExecutorService reader) {
    this.http = http;
    this.reader = reader;
  }

  /**
   * Opens the stream and returns once the response headers have arrived.
   *
   * <p>Spring commits the response as it starts async processing, which happens only after the
   * handler has already registered the subscriber. Waiting for the headers is therefore what makes
   * "subscribe, then cause an event" deterministic instead of a race.
   *
   * @param lastEventId sent as {@code Last-Event-ID}, or null for a fresh subscription
   */
  public static SseTestClient connect(URI uri, String lastEventId, Duration timeout)
      throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri).GET().header("Accept", "text/event-stream").timeout(timeout);
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }

    HttpResponse<Stream<String>> response =
        http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (response.statusCode() != 200) {
      http.close();
      throw new IllegalStateException("Stream refused with status " + response.statusCode());
    }

    ExecutorService reader =
        Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("sse-test-reader").unstarted(runnable));
    SseTestClient client = new SseTestClient(http, reader);
    reader.execute(() -> client.pump(response.body()));
    return client;
  }

  /** @return the next frame, or empty when none arrived inside {@code timeout} */
  public Optional<Frame> nextFrame(Duration timeout) throws InterruptedException {
    return Optional.ofNullable(frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
  }

  /** Reads {@code count} frames or fails the wait, so a test never blocks without a bound. */
  public List<Frame> nextFrames(int count, Duration timeout) throws InterruptedException {
    List<Frame> received = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Frame frame =
          nextFrame(timeout)
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Expected " + count + " event(s) within " + timeout + " but received "
                              + received.size()));
      received.add(frame);
    }
    return received;
  }

  @Override
  public void close() {
    // shutdownNow rather than close: close() waits for the in-flight stream, which by design never
    // finishes on its own.
    http.shutdownNow();
    reader.shutdownNow();
  }

  private void pump(Stream<String> lines) {
    String id = null;
    String event = null;
    StringBuilder data = new StringBuilder();
    try {
      for (String line : (Iterable<String>) lines::iterator) {
        if (line.isEmpty()) {
          if (event != null || data.length() > 0) {
            frames.add(new Frame(id, event, data.toString()));
          }
          id = null;
          event = null;
          data.setLength(0);
          continue;
        }
        if (line.startsWith(":")) {
          continue; // A heartbeat comment.
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : stripLeadingSpace(line.substring(colon + 1));
        switch (field) {
          case "id" -> id = value;
          case "event" -> event = value;
          case "data" -> data.append(data.length() == 0 ? "" : "\n").append(value);
          default -> {
            // "retry" and anything else the transport adds later are not asserted on.
          }
        }
      }
    } catch (RuntimeException closed) {
      // Expected on close(): the underlying connection is torn down under a blocked read.
    }
  }

  private static String stripLeadingSpace(String value) {
    return value.startsWith(" ") ? value.substring(1) : value;
  }

  /** One delivered event: the {@code id:}, {@code event:} and {@code data:} fields of a frame. */
  public record Frame(String id, String event, String data) {}
}
