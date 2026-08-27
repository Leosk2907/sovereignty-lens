package eu.sovereigntylens.domain.port;

/**
 * Wakes live consumers when the durable event log has moved.
 *
 * <p>This port exists so the database bridge does not have to know what a live consumer is. Both
 * ends are adapters - a {@code LISTEN}/{@code NOTIFY} loop in {@code adapter.persistence} and the
 * Server-Sent Events fan-out in {@code adapter.web} - and letting one import the other would point
 * a dependency arrow sideways, which the architecture forbids. Two one-line methods pointing inward
 * cost less than that.
 *
 * <p>Every method here is required to return promptly. The only caller is the single thread that
 * polls Postgres for notifications, and anything it waits on is time the whole installation spends
 * receiving nothing.
 */
public interface GraphEventNotifier {

  /**
   * A graph event has committed and its durable row is readable.
   *
   * @param eventId the event's identity, which is the entire {@code pg_notify} payload; the
   *     authoritative bytes are read back from the log by the implementation, off the caller's
   *     thread
   */
  void eventCommitted(String eventId);

  /**
   * The notification bridge reconnected, so notifications that fired while it was down are gone for
   * good and Postgres will not resend them.
   *
   * <p>The gap is closed from the durable log instead, per consumer, from the position each one
   * actually reached.
   */
  void notificationsMayHaveBeenMissed();
}
