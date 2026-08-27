package eu.sovereigntylens.domain.port;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.Session;
import java.util.Optional;

/** Outbound port for reading the authoritative session state every use case needs. */
public interface SessionRepository {

  Optional<Session> findBySlug(String slug);

  /**
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  default Session requireBySlug(String slug) {
    return findBySlug(slug).orElseThrow(() -> DomainException.sessionNotFound(slug));
  }
}
