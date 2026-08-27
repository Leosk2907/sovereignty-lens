package eu.sovereigntylens.adapter.web;

import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.domain.model.StoredGraphEvent;
import eu.sovereigntylens.domain.port.GraphEventNotifier;
import eu.sovereigntylens.domain.port.GraphEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process fan-out of committed graph events to the connected presentation screens.
 *
 * <p>One instance holds every open Server-Sent Events stream. It lives in {@code adapter.web}
 * because it speaks {@link SseEmitter} and {@link MediaType}, which are HTTP transport types; the
 * database bridge reaches it through {@link GraphEventNotifier} so that no adapter imports another.
 *
 * <p>Delivery is best effort by design: the database is the source of truth and every consumer
 * reconciles against {@code GET /api/sessions/{slug}/graph}, so a dropped or duplicated event costs
 * a reconciliation, never correctness. What this class does guarantee is ascending sequence order
 * per subscriber, and at most one delivery of any {@code dependency.created}. A {@code
 * graph.invalidated} may repeat, because it is idempotent and suppressing one is far worse than
 * sending it twice - see {@link Subscriber#write}.
 *
 * <h2>Threading</h2>
 *
 * <p>No thread that serves more than one subscriber is ever allowed to block on a subscriber's
 * socket. Concretely:
 *
 * <ul>
 *   <li>The Postgres listener thread only offers an event id onto {@link #committed} and returns.
 *   <li>One long-lived task drains {@link #committed}, reads each event back from the log, and
 *       offers it onto every interested subscriber's own bounded queue. Offers never block, so one
 *       stalled projector cannot delay the next one.
 *   <li>Each subscriber has one long-lived task of its own that drains its queue and performs the
 *       blocking writes. Those tasks run on virtual threads, so a write that parks for minutes
 *       costs a few kilobytes of stack rather than a platform thread.
 *   <li>The heartbeat scheduler enqueues a signal per subscriber; it too never writes.
 * </ul>
 *
 * <p>The only lock in the path is a {@link ReentrantLock} per subscriber, and it is held only
 * across emitter calls, never across a database query. It is deliberately not a monitor: on JDK 21
 * a virtual thread that blocks inside {@code synchronized} pins its carrier, and with {@code
 * spring.threads.virtual.enabled} every socket write here would do exactly that.
 */
@Component
public class GraphEventBroadcaster implements GraphEventNotifier {

  private static final Logger log = LoggerFactory.getLogger(GraphEventBroadcaster.class);

  /**
   * Upper bound on rows replayed to one subscriber when it resumes or when the listener reconnects.
   *
   * <p>A projector that was offline for an entire round would otherwise be served thousands of rows
   * before its first live event, which is both slow and pointless: the client fetches a full
   * snapshot on reconnect anyway. Capping trades a complete replay, which nobody needs, for a
   * bounded one.
   */
  private static final int REPLAY_LIMIT = 500;

  /**
   * How far one subscriber may fall behind before it is dropped instead of slowing anyone down.
   *
   * <p>A queue this deep already means several seconds of unwritten frames on a link that is not
   * draining. Completing the emitter costs that screen a reconnect, after which it fetches the
   * authoritative snapshot and is correct again; letting it keep its slot would cost every other
   * screen its live updates.
   */
  private static final int SUBSCRIBER_QUEUE_CAPACITY = 128;

  /**
   * How many committed-but-unread notifications may be in flight before they are discarded.
   *
   * <p>Sized well above any plausible burst from one room of phones. Overflowing means the log
   * reader cannot keep up, and discarding is the correct response: the event ids are recoverable
   * from the durable log, and every client reconciles against the snapshot regardless.
   */
  private static final int PENDING_LOOKUP_CAPACITY = 1_024;

  /** Never suppressed by watermark; see {@link Subscriber#write}. */
  private static final String GRAPH_INVALIDATED = "graph.invalidated";

  private final Map<String, Set<Subscriber>> subscribersBySession = new ConcurrentHashMap<>();
  private final BlockingQueue<String> committed = new ArrayBlockingQueue<>(PENDING_LOOKUP_CAPACITY);
  private final GraphEventRepository events;
  private final Duration heartbeatInterval;
  private final Duration connectionTimeout;
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * Carries the log reader and one drain task per subscriber.
   *
   * <p>Virtual threads, because every task here exists to block on I/O: a socket that has stopped
   * accepting bytes, or a JDBC round trip. A fixed platform pool would let a handful of stalled
   * projectors occupy every worker and reproduce the very problem this design removes, and a
   * platform thread per projector would be a megabyte of stack per screen.
   */
  private final ExecutorService deliveries =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("sse-delivery-", 0).factory());

  /**
   * One scheduler for every stream. It only enqueues, so it cannot be delayed by a subscriber, and
   * a thread per projector to write four bytes every fifteen seconds would still be waste.
   */
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(threadFactory("sse-heartbeat"));

  public GraphEventBroadcaster(GraphEventRepository events, AppProperties properties) {
    this.events = events;
    this.heartbeatInterval = properties.sseHeartbeatInterval();
    this.connectionTimeout = properties.sseConnectionTimeout();
  }

  @PostConstruct
  void start() {
    running.set(true);
    deliveries.execute(this::readCommittedEvents);
    long period = Math.max(1L, heartbeatInterval.toMillis());
    heartbeats.scheduleAtFixedRate(this::sendHeartbeats, period, period, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void shutdown() {
    running.set(false);
    heartbeats.shutdownNow();
    // Every subscriber is marked closed before the executor is torn down, so each drain task
    // completes its own emitter on the way out instead of leaving a half-open response behind.
    subscribersBySession.values().forEach(set -> set.forEach(this::drop));
    subscribersBySession.clear();
    deliveries.shutdownNow();
  }

  /**
   * Opens a stream for one session.
   *
   * @param fromSequence the position a resuming client has already seen, or null for a fresh
   *     client, which starts at the current head of the log because it has just fetched a snapshot
   * @return an emitter the caller returns from a {@code text/event-stream} handler
   */
  public SseEmitter subscribe(String sessionSlug, Long fromSequence) {
    long watermark = fromSequence == null ? events.latestSequence(sessionSlug) : fromSequence;
    Subscriber subscriber =
        new Subscriber(sessionSlug, new SseEmitter(connectionTimeout.toMillis()), watermark);

    // The replay is queued before the subscriber is visible to publish(), which is what keeps
    // replayed events ahead of live ones without holding anything: the queue is still private here,
    // so the replay is unconditionally first, and everything that commits from now on either lands
    // in the replay query or arrives behind it.
    subscriber.queue.offer(Signal.REPLAY);

    subscriber.emitter.onCompletion(() -> drop(subscriber));
    subscriber.emitter.onTimeout(() -> drop(subscriber));
    subscriber.emitter.onError(error -> drop(subscriber));
    subscribersBySession.compute(
        sessionSlug,
        (slug, existing) -> {
          Set<Subscriber> set = existing == null ? ConcurrentHashMap.newKeySet() : existing;
          set.add(subscriber);
          return set;
        });

    try {
      deliveries.execute(() -> drain(subscriber));
    } catch (RejectedExecutionException e) {
      // Only reachable during shutdown. Nothing will ever write to this emitter, so it is closed
      // here rather than left for a client to wait on.
      log.debug("Refusing a stream for session {} while shutting down", sessionSlug, e);
      drop(subscriber);
    }
    return subscriber.emitter;
  }

  /**
   * Translates a {@code Last-Event-ID} header into a resume position.
   *
   * @return empty when the header is absent, malformed, or names an event the log no longer knows;
   *     the caller then starts live rather than failing, because the client reconciles anyway
   */
  public Optional<Long> resumeSequence(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return Optional.empty();
    }
    return events.findSequenceByEventId(lastEventId.trim());
  }

  /**
   * Accepts a committed event id from the notification bridge and returns immediately.
   *
   * <p>Reading the row back is a database round trip that can wait on the connection pool, so it
   * happens on {@link #deliveries} rather than on the single thread whose only job is to keep
   * polling Postgres.
   */
  @Override
  public void eventCommitted(String eventId) {
    if (eventId == null || eventId.isBlank()) {
      return;
    }
    if (!committed.offer(eventId)) {
      log.warn("Dropping graph event {}: the delivery queue is saturated", eventId);
    }
  }

  /**
   * Re-sends everything each open stream has missed. Called after the notification bridge
   * reconnects, when notifications that fired while it was disconnected are gone for good: the gap
   * is closed from the durable log instead, per subscriber, from the last sequence that subscriber
   * actually received.
   */
  @Override
  public void notificationsMayHaveBeenMissed() {
    subscribersBySession.values().forEach(set -> set.forEach(this::requestReplay));
  }

  /**
   * Fans one committed event out to the screens watching its session.
   *
   * <p>Filtering is by session slug only. A subscriber is deliberately <em>not</em> filtered by
   * round: after a reset, a client still displaying the previous round only learns that the round
   * changed by receiving the {@code graph.invalidated} event that carries the new one. Filtering
   * server-side by the round the client subscribed with would strand exactly the clients the event
   * exists to rescue. Discarding events for the wrong round is the consumer's job, as the transport
   * amendment specifies.
   *
   * <p>This only enqueues. The caller is never held up by a screen that has stopped reading.
   */
  public void publish(StoredGraphEvent event) {
    Set<Subscriber> targets = subscribersBySession.get(event.sessionSlug());
    if (targets == null) {
      return;
    }
    for (Subscriber subscriber : targets) {
      offer(subscriber, Signal.of(event));
    }
  }

  /** Open streams, exposed for the health of a live demo rather than for logic. */
  public int subscriberCount() {
    return subscribersBySession.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Turns notification payloads into events, one at a time.
   *
   * <p>Serial rather than a task per notification: the fan-out below writes in the order it is
   * called, and parallel lookups would let a later event overtake an earlier one, at which point
   * the watermark would suppress the earlier one for good.
   */
  private void readCommittedEvents() {
    while (running.get()) {
      String eventId;
      try {
        eventId = committed.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      try {
        events
            .findByEventId(eventId)
            .ifPresentOrElse(
                this::publish,
                () ->
                    log.warn(
                        "Notification named an event that is not in graph_events: {}", eventId));
      } catch (RuntimeException e) {
        // A failed lookup or fan-out must not end this loop: it is the only path live events have,
        // the next notification is likely to work, and clients reconcile against the snapshot.
        log.warn("Could not deliver graph event {}", eventId, e);
      }
    }
    log.debug("Graph event reader stopped");
  }

  /**
   * Drains one subscriber's queue for the life of its stream.
   *
   * <p>Everything that touches this emitter happens here, on one virtual thread, which is what
   * makes the ordering guarantee hold without a lock around the queue and what confines {@code
   * watermark} to a single thread.
   */
  private void drain(Subscriber subscriber) {
    // Published before the first read of closed, so a drop racing the start of this task either
    // sees the thread and interrupts it or is seen by the check below. One of the two always holds.
    subscriber.drainThread = Thread.currentThread();
    try {
      while (!subscriber.closed.get()) {
        Signal signal = subscriber.queue.take();
        if (!handle(subscriber, signal)) {
          break;
        }
      }
    } catch (InterruptedException e) {
      // An interrupt here means this subscriber was dropped, which is the normal way a stream ends.
      // The flag is deliberately not restored: the task stops at this line either way, and leaving
      // it set would only make the closing write below fail.
      log.debug("Delivery for session {} was stopped", subscriber.sessionSlug, e);
    } catch (Throwable t) {
      // A stream is never worth taking the process down for, and the client reconnects.
      log.warn("Delivery for session {} failed", subscriber.sessionSlug, t);
    } finally {
      remove(subscriber);
      subscriber.closed.set(true);
      subscriber.completeSilently();
    }
  }

  /** Returns false when the stream is gone and this subscriber should be torn down. */
  private boolean handle(Subscriber subscriber, Signal signal) {
    return switch (signal.kind()) {
      case EVENT -> subscriber.write(signal.event());
      case HEARTBEAT -> subscriber.heartbeat();
      case REPLAY -> replay(subscriber);
    };
  }

  /**
   * Closes this subscriber's gap from the durable log.
   *
   * <p>The query runs before any lock is taken and outside every one of them. Ordering is not at
   * risk: live events for this subscriber are sitting in its queue behind the signal being handled
   * here, and nothing but this thread writes to its emitter.
   */
  private boolean replay(Subscriber subscriber) {
    List<StoredGraphEvent> missed;
    try {
      missed = events.findAfterSequence(subscriber.sessionSlug, subscriber.watermark, REPLAY_LIMIT);
    } catch (RuntimeException e) {
      // A failed replay must not fail the subscription: the client reconciles against the snapshot.
      log.warn("Could not replay missed events for session {}", subscriber.sessionSlug, e);
      return true;
    }
    for (StoredGraphEvent event : missed) {
      if (!subscriber.write(event)) {
        return false;
      }
    }
    return true;
  }

  private void sendHeartbeats() {
    // scheduleAtFixedRate cancels the task for good if it ever throws, which would silently end
    // every heartbeat in the process, so nothing is allowed to escape.
    try {
      subscribersBySession
          .values()
          .forEach(set -> set.forEach(subscriber -> offer(subscriber, Signal.HEARTBEAT)));
    } catch (Throwable t) {
      log.warn("Heartbeat round failed", t);
    }
  }

  private void requestReplay(Subscriber subscriber) {
    offer(subscriber, Signal.REPLAY);
  }

  /**
   * Hands one signal to a subscriber without ever waiting for it.
   *
   * <p>A full queue is the definition of a screen that is not keeping up, and the producer here is
   * shared by every screen in the room. Dropping the slow one is the only option that keeps the
   * others live; it reconnects and reconciles against the snapshot, which is the authoritative
   * source anyway, so the cost is one refetch on one projector rather than a frozen wall.
   */
  private void offer(Subscriber subscriber, Signal signal) {
    if (subscriber.closed.get()) {
      return;
    }
    if (!subscriber.queue.offer(signal)) {
      log.info("Dropping a subscriber of session {}: it stopped reading", subscriber.sessionSlug);
      drop(subscriber);
    }
  }

  /**
   * Unregisters a subscriber and stops its drain task.
   *
   * <p>Interruption rather than a sentinel, because the reason for dropping is usually that the
   * queue is full or the write is stuck; the drain task closes the emitter in its own {@code
   * finally}, so no other thread waits on that socket.
   */
  private void drop(Subscriber subscriber) {
    remove(subscriber);
    if (!subscriber.closed.compareAndSet(false, true)) {
      return;
    }
    Thread worker = subscriber.drainThread;
    if (worker == null) {
      // The drain task has not reached its first instruction, or was never scheduled at all. It
      // will exit on the closed flag; completing here covers the case where it never runs.
      subscriber.completeSilently();
    } else if (worker != Thread.currentThread()) {
      worker.interrupt();
    }
  }

  private void remove(Subscriber subscriber) {
    subscribersBySession.computeIfPresent(
        subscriber.sessionSlug,
        (slug, set) -> {
          set.remove(subscriber);
          return set.isEmpty() ? null : set;
        });
  }

  private static ThreadFactory threadFactory(String name) {
    return runnable -> Thread.ofPlatform().daemon().name(name).unstarted(runnable);
  }

  /** What a subscriber's drain task is asked to do next. */
  private enum Kind {
    EVENT,
    HEARTBEAT,
    REPLAY
  }

  private record Signal(Kind kind, StoredGraphEvent event) {

    private static final Signal HEARTBEAT = new Signal(Kind.HEARTBEAT, null);
    private static final Signal REPLAY = new Signal(Kind.REPLAY, null);

    private static Signal of(StoredGraphEvent event) {
      return new Signal(Kind.EVENT, event);
    }
  }

  /**
   * One open stream, its backlog, and the position it has reached.
   *
   * <p>{@code watermark} carries no synchronization because only the drain task reads or writes it,
   * and the initial value is published to that task by the executor call that starts it.
   */
  private static final class Subscriber {

    private final String sessionSlug;
    private final SseEmitter emitter;
    private final BlockingQueue<Signal> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Guards every emitter call. Servlet response output is not thread safe, and completion can
     * race a write during shutdown.
     *
     * <p>A {@link ReentrantLock} rather than {@code synchronized}: the drain task is a virtual
     * thread and it blocks on a socket while holding this, which inside a monitor would pin its
     * carrier for the duration on JDK 21.
     */
    private final ReentrantLock emitterLock = new ReentrantLock();

    /** The one thread allowed to write here, published so a drop can unblock it. */
    private volatile Thread drainThread;

    private long watermark;

    private Subscriber(String sessionSlug, SseEmitter emitter, long watermark) {
      this.sessionSlug = sessionSlug;
      this.emitter = emitter;
      this.watermark = watermark;
    }

    /** Returns false when the stream is gone and the subscriber should be unregistered. */
    private boolean heartbeat() {
      emitterLock.lock();
      try {
        emitter.send(SseEmitter.event().comment("ping"));
        return true;
      } catch (IOException | IllegalStateException e) {
        return false;
      } finally {
        emitterLock.unlock();
      }
    }

    private boolean write(StoredGraphEvent event) {
      // graph.invalidated is never suppressed. Sequences come from a bigserial, assigned at insert
      // rather than at commit, so a hide or a reset can be given a lower sequence than a
      // contribution that commits before it; suppressing it by watermark would leave the presenter
      // clicking a control that changes nothing on screen. The event is idempotent - it only tells
      // the client to reconcile - so a repeat is free, and the client already deduplicates by
      // eventId.
      if (event.sequence() <= watermark && !GRAPH_INVALIDATED.equals(event.eventType())) {
        return true; // Already sent, by the other of the two paths that can send it.
      }
      emitterLock.lock();
      try {
        emitter.send(
            SseEmitter.event()
                .id(event.eventId())
                .name(event.eventType())
                // The payload is already canonical JSON. text/plain is not a description of the
                // content but the one media type that guarantees Spring picks the plain string
                // converter: handing Jackson a String and application/json would re-encode it as a
                // quoted JSON string and corrupt the frame.
                .data(event.payloadJson(), MediaType.TEXT_PLAIN));
        // Never moves backwards: an out-of-order invalidation must not reopen a window that older
        // events could be replayed through.
        watermark = Math.max(watermark, event.sequence());
        return true;
      } catch (IOException | IllegalStateException e) {
        // Routine: a closed tab, a reloaded projector, a dropped Wi-Fi link.
        log.debug("Dropping subscriber of session {}", sessionSlug, e);
        return false;
      } finally {
        emitterLock.unlock();
      }
    }

    private void completeSilently() {
      // tryLock, not lock: this can run on a shutdown thread while the drain task is parked on a
      // socket that stopped draining, and waiting there is exactly the stall being designed out.
      // The drain task completes the emitter itself when it unblocks, so nothing leaks.
      if (!emitterLock.tryLock()) {
        return;
      }
      try {
        emitter.complete();
      } catch (RuntimeException e) {
        log.debug("Emitter for session {} was already finished", sessionSlug, e);
      } finally {
        emitterLock.unlock();
      }
    }
  }
}
