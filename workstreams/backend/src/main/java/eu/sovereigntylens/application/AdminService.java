package eu.sovereigntylens.application;

import eu.sovereigntylens.domain.model.AdminDependencyView;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.port.AdminRepository;
import eu.sovereigntylens.domain.port.SessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The presenter's recovery controls.
 *
 * <p>Every method is one transaction, which is what makes the state change and the {@code
 * graph.invalidated} event that announces it a single fact. The service speaks domain models only;
 * the web adapter owns the wire format and the mapper owns the translation.
 *
 * <p>Authentication is not decided here. It is an HTTP concern, enforced once by the admin
 * interceptor for the whole {@code /api/admin} tree, so no use case can be reached unauthenticated
 * by forgetting a check.
 */
@Service
@Transactional
public class AdminService {

  private final AdminRepository admin;
  private final SessionRepository sessions;

  public AdminService(AdminRepository admin, SessionRepository sessions) {
    this.admin = admin;
    this.sessions = sessions;
  }

  /** Stops the session accepting contributions. */
  public AdminOutcome pause(String sessionSlug) {
    return admin.pause(sessionSlug);
  }

  /** Returns the authoritative session shown by the presenter shell. */
  @Transactional(readOnly = true)
  public Session session(String sessionSlug) {
    return sessions.requireBySlug(sessionSlug);
  }

  /** Reopens the session. */
  public AdminOutcome resume(String sessionSlug) {
    return admin.resume(sessionSlug);
  }

  /** Starts the next round and reopens the session. Earlier rounds are kept, not deleted. */
  public AdminOutcome reset(String sessionSlug) {
    return admin.reset(sessionSlug);
  }

  /**
   * Hides the newest audience dependency of the current round.
   *
   * <p>With nothing left to undo the store changes nothing and reports it honestly. The presenter
   * still gets an answer carrying the authoritative session, and connected clients are still told
   * to reconcile - an invalidation is only ever a hint to refetch, so announcing one costs a
   * snapshot read and removes any doubt about whose view is stale.
   */
  public AdminOutcome undo(String sessionSlug) {
    return admin
        .undo(sessionSlug)
        .orElseGet(() -> admin.invalidate(sessionSlug, AdminRepository.Reason.UNDO));
  }

  /** The presenter's review list for the round currently on screen, hidden entries included. */
  @Transactional(readOnly = true)
  public DependencyListing listDependencies(String sessionSlug) {
    // The session is read in the same transaction as the rows, so the round the list is labelled
    // with is the round the list was actually taken from.
    Session session = sessions.requireBySlug(sessionSlug);
    return new DependencyListing(session, admin.listCurrentRoundDependencies(sessionSlug));
  }

  /** Hides or restores one current-round audience dependency. */
  public AdminRepository.DependencyOutcome setDependencyStatus(
      UUID dependencyId, DependencyStatus status) {
    return admin.setStatus(dependencyId, status);
  }

  /**
   * The review list together with the session state it was read with.
   *
   * <p>They travel as one value because the presenter screen shows the round number next to the
   * rows; reading them separately would let a reset land between the two and label the list wrong.
   */
  public record DependencyListing(Session session, List<AdminDependencyView> dependencies) {}
}
