package eu.sovereigntylens.domain.port;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.AdminDependencyView;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.DependencyStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Presenter control over a running session.
 *
 * <p>Every method here changes what the audience sees, so each one is a single port call: the
 * mutation and the {@code graph.invalidated} event that tells clients to refetch must be committed
 * together or not at all. An implementation that wrote the row and announced it afterwards would
 * leave every presentation screen showing a graph the database no longer agrees with.
 *
 * <p>Nothing is ever deleted. Hiding, undo and reset are all reversible or additive, because the
 * only recovery a presenter has on stage is the one that does not destroy the previous state.
 */
public interface AdminRepository {

  /**
   * Pauses the session so it stops accepting contributions.
   *
   * <p>Safe to call on an already paused session: the presenter is recovering, not transacting, and
   * a second click must not fail.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  AdminOutcome pause(String sessionSlug);

  /**
   * Reopens the session. Idempotent for the same reason as {@link #pause(String)}.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  AdminOutcome resume(String sessionSlug);

  /**
   * Starts a new round and reopens the session.
   *
   * <p>The previous round's rows stay in the database untouched; they simply stop matching the
   * current round and so drop out of the public graph. The returned session carries the new round,
   * which is also the round the emitted event announces.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  AdminOutcome reset(String sessionSlug);

  /**
   * Hides the most recently added active audience dependency of the current round.
   *
   * @return the outcome, or empty when the current round holds nothing that could be undone. That
   *     is a legitimate no-op, not a failure: the presenter pressed undo once too often. Nothing is
   *     written and no event is emitted, so the implementation never reports a change it did not
   *     make.
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  Optional<AdminOutcome> undo(String sessionSlug);

  /**
   * Emits a {@code graph.invalidated} event without changing any data.
   *
   * <p>Exists for the no-op branch of undo, where the presenter still needs an answer and connected
   * clients still benefit from reconciling against the authoritative snapshot.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  AdminOutcome invalidate(String sessionSlug, Reason reason);

  /**
   * All current-round, non-seed dependencies including hidden ones, newest first.
   *
   * <p>Hidden rows are deliberately included: this is the screen a presenter uses to put something
   * back after hiding it.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  List<AdminDependencyView> listCurrentRoundDependencies(String sessionSlug);

  /**
   * Hides or restores one dependency.
   *
   * @throws DomainException with {@code NOT_FOUND} when the dependency does not exist, is a seed
   *     row, or belongs to an earlier round - a presenter may only edit the round on screen; with
   *     {@code DUPLICATE_DEPENDENCY} when restoring would produce a second active copy of an edge
   *     that is already active in this round
   */
  DependencyOutcome setStatus(UUID dependencyId, DependencyStatus status);

  /** What a hide or restore produced. */
  record DependencyOutcome(String eventId, Dependency dependency) {}

  /**
   * Why the graph was invalidated.
   *
   * <p>The domain twin of the contract enum, mapped one-to-one by name, so that the reason a
   * presenter action gives cannot be changed by a wire-format decision.
   */
  enum Reason {
    PAUSE("pause"),
    RESUME("resume"),
    HIDE("hide"),
    RESTORE("restore"),
    UNDO("undo"),
    RESET("reset");

    private final String wireValue;

    Reason(String wireValue) {
      this.wireValue = wireValue;
    }

    /** The token carried by the emitted event. */
    public String wireValue() {
      return wireValue;
    }
  }
}
