package eu.sovereigntylens.adapter.persistence;

import eu.sovereigntylens.domain.port.GraphEventNotifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Bridges Postgres {@code LISTEN}/{@code NOTIFY} to the in-process live stream.
 *
 * <p>Writers call {@code emit_graph_event}, which inserts the durable row and calls {@code
 * pg_notify} inside their own transaction. Postgres delivers the notification only on commit, so a
 * rolled-back contribution produces neither row nor wake-up and this class can treat every
 * notification as a committed fact. The payload is only the event id; the authoritative bytes are
 * read back from {@code graph_events} by the {@link GraphEventNotifier}, on its own threads.
 *
 * <p>This thread does as little as it possibly can: read the notification, hand the id over, go
 * back to polling. It used to look the row up itself, which meant borrowing a pooled connection per
 * notification and, when the pool was busy, waiting up to the thirty-second Hikari timeout - during
 * which nothing reached any screen in the room, because this is the only thread that receives
 * notifications at all.
 *
 * <p>Listening needs a connection that is never handed back, because a pooled connection would stop
 * listening the moment another caller borrowed it. One connection out of the pool of twelve is
 * therefore checked out for the lifetime of the process, which {@code application.yml} accounts for.
 *
 * <p>The demo is live, so the loop is written to survive rather than to be elegant: every failure
 * reconnects with capped backoff, and every reconnect closes the gap from the durable log, since
 * notifications that fired while the socket was down are gone and Postgres will not resend them.
 */
@Component
public class PostgresNotificationListener implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PostgresNotificationListener.class);

  /** Must match the channel name in {@code emit_graph_event}. */
  static final String CHANNEL = "sovereignty_graph_events";

  /**
   * How long one poll blocks. Also the interval of the liveness probe below: a shorter timeout burns
   * queries, a longer one delays noticing a silently dead socket.
   */
  private static final int POLL_TIMEOUT_MILLIS = 10_000;

  private static final long INITIAL_BACKOFF_MILLIS = 1_000L;
  private static final long MAX_BACKOFF_MILLIS = 30_000L;

  private final DataSource dataSource;
  private final GraphEventNotifier notifier;

  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * Guards the backoff wait. A lock rather than a monitor, matching the rest of the live-event path:
   * nothing here may become a construct that pins a carrier if this ever moves to a virtual thread.
   */
  private final ReentrantLock idleLock = new ReentrantLock();

  private final Condition idle = idleLock.newCondition();

  /** Held so {@link #stop()} can break a blocked poll by closing the socket underneath it. */
  private volatile Connection listening;

  private volatile Thread worker;

  public PostgresNotificationListener(DataSource dataSource, GraphEventNotifier notifier) {
    this.dataSource = dataSource;
    this.notifier = notifier;
  }

  /**
   * Started last and stopped first, which is the default {@code SmartLifecycle} phase and the one we
   * want: the pool must be up before the listener connects and must still be up while it closes.
   */
  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    // A platform thread, not a virtual one: this thread spends its life blocked in a socket read
    // inside the driver, which is exactly the workload a carrier thread should not be pinned by.
    Thread thread = Thread.ofPlatform().daemon().name("pg-graph-events").unstarted(this::listen);
    worker = thread;
    thread.start();
  }

  @Override
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    idleLock.lock();
    try {
      idle.signalAll(); // Cuts a backoff sleep short.
    } finally {
      idleLock.unlock();
    }
    closeQuietly(listening); // Unblocks a poll that is waiting on the socket.
    Thread thread = worker;
    if (thread != null) {
      try {
        // Bounded: the poll returns on its own within POLL_TIMEOUT_MILLIS and the thread is a
        // daemon, so a slow exit must not hold up shutdown of the rest of the service.
        thread.join(2_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    worker = null;
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void listen() {
    long backoff = INITIAL_BACKOFF_MILLIS;
    while (running.get()) {
      try {
        Connection connection = open();
        listening = connection;
        try {
          log.info("Listening for graph events on {}", CHANNEL);
          backoff = INITIAL_BACKOFF_MILLIS;
          // Notifications missed while disconnected are unrecoverable from Postgres, so the gap is
          // closed from the durable log instead, per subscriber, from where each one actually got
          // to. On the very first connect this is a no-op: nobody is subscribed yet.
          notifier.notificationsMayHaveBeenMissed();
          pump(connection);
        } finally {
          listening = null;
          closeQuietly(connection);
        }
      } catch (Throwable t) {
        // Throwable, not Exception: this thread is the only path live events have, and a silent
        // death would leave the projectors showing a graph that never changes again.
        if (!running.get()) {
          break;
        }
        log.warn("Graph event listener lost its connection, retrying in {}ms", backoff, t);
        if (sleepBeforeRetry(backoff)) {
          break;
        }
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
      }
    }
    log.info("Graph event listener stopped");
  }

  private Connection open() throws SQLException {
    Connection connection = dataSource.getConnection();
    try {
      // LISTEN must not sit inside an open transaction: the driver only surfaces notifications
      // while the connection is idle.
      connection.setAutoCommit(true);
      try (Statement statement = connection.createStatement()) {
        statement.execute("listen " + CHANNEL);
      }
      return connection;
    } catch (SQLException | RuntimeException e) {
      closeQuietly(connection);
      throw e;
    }
  }

  private void pump(Connection connection) throws SQLException {
    PGConnection pgConnection = unwrap(connection);
    while (running.get()) {
      PGNotification[] notifications = pgConnection.getNotifications(POLL_TIMEOUT_MILLIS);
      if (notifications == null || notifications.length == 0) {
        probe(connection);
        continue;
      }
      for (PGNotification notification : notifications) {
        // Hand-off only. Reading the row and fanning it out happen elsewhere so that this loop is
        // back inside getNotifications within microseconds.
        notifier.eventCommitted(notification.getParameter());
      }
    }
  }

  /**
   * {@code LISTEN}/{@code NOTIFY} is a driver-specific API with no JDBC equivalent, so the pooled
   * connection has to be a real PostgreSQL one.
   *
   * <p>A pool or proxy that hands out something else would otherwise produce a stream that simply
   * never emits, which on a stage looks identical to a quiet audience. Failing loudly at startup is
   * the only useful behaviour.
   */
  private static PGConnection unwrap(Connection connection) throws SQLException {
    if (!connection.isWrapperFor(PGConnection.class)) {
      throw new IllegalStateException(
          "Live graph events need a PostgreSQL connection, but the pool returned "
              + connection.getClass().getName());
    }
    return connection.unwrap(PGConnection.class);
  }

  /**
   * A blocked read does not notice a connection that a firewall or NAT table quietly discarded, and
   * an idle listener is exactly the kind of connection middleboxes discard. A round trip after every
   * quiet poll turns that into a normal reconnect instead of permanent silence.
   */
  private void probe(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("select 1");
    }
  }

  /** Returns true when the wait ended because the component is shutting down. */
  private boolean sleepBeforeRetry(long millis) {
    idleLock.lock();
    try {
      idle.await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    } finally {
      idleLock.unlock();
    }
    return !running.get();
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException | RuntimeException e) {
      log.debug("Listening connection did not close cleanly", e);
    }
  }
}
