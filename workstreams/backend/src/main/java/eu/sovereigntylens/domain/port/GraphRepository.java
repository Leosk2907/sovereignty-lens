package eu.sovereigntylens.domain.port;

import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.Session;
import java.util.List;

/**
 * Outbound port for reading the public graph.
 *
 * <p>Nodes and edges are fetched separately rather than as one assembled {@code GraphView} so that
 * the application layer stays the place where the "every edge references a present node" invariant
 * is checked, and so a caller that only needs one half does not pay for the other.
 *
 * <p>Both methods return the deterministic order the data contract mandates: creation time, then
 * id. Clients still address records by id, but a stable order keeps the presentation layout from
 * jumping between polls.
 */
public interface GraphRepository {

  /**
   * Organizations incident to a visible edge, plus the session root organization even when it has
   * no edges yet.
   */
  List<Organization> findVisibleNodes(Session session);

  /**
   * Active seed dependencies, which belong to no round, plus active audience dependencies from
   * {@code session.currentRound()}. Hidden dependencies are never returned.
   */
  List<Dependency> findVisibleEdges(Session session);
}
