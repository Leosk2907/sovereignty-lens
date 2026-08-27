package eu.sovereigntylens.application;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.GraphView;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.port.GraphRepository;
import eu.sovereigntylens.domain.port.SessionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: read the public graph of one session.
 *
 * <p>This is the authoritative read behind every client. The presentation calls it at load, after a
 * {@code graph.invalidated} event, after a reconnect, and as its polling fallback, so it must be
 * cheap and must never return a partially consistent picture. Nodes and edges are therefore read
 * inside one read-only transaction: a contribution committing between the two queries would
 * otherwise produce an edge whose target node is missing.
 */
@Service
public class GraphQueryService {

  private static final Logger log = LoggerFactory.getLogger(GraphQueryService.class);

  private final SessionRepository sessions;
  private final GraphRepository graph;

  public GraphQueryService(SessionRepository sessions, GraphRepository graph) {
    this.sessions = sessions;
    this.graph = graph;
  }

  /**
   * Loads the visible graph of the session with this slug.
   *
   * @throws DomainException with {@code SESSION_NOT_FOUND} when the slug is unknown
   */
  @Transactional(readOnly = true)
  public GraphView load(String slug) {
    Session session = sessions.requireBySlug(slug);
    List<Organization> nodes = graph.findVisibleNodes(session);
    List<Dependency> edges = graph.findVisibleEdges(session);
    return consistent(new GraphView(session, nodes, edges));
  }

  /**
   * Enforces the contract invariant "every edge references nodes present in the same snapshot".
   *
   * <p>The node query is written so this can never fire; if it does, the visibility rules of the two
   * queries have drifted apart and that is a bug. It is logged at error level to be noticed, but the
   * dangling edge is dropped rather than shipped: a client that receives an edge pointing at nothing
   * renders a broken graph in front of an audience, which is worse than one missing line.
   */
  private GraphView consistent(GraphView view) {
    Set<String> nodeIds = new HashSet<>();
    view.nodes().forEach(node -> nodeIds.add(node.id()));

    List<Dependency> connected =
        view.edges().stream()
            .filter(
                edge ->
                    nodeIds.contains(edge.sourceOrganizationId())
                        && nodeIds.contains(edge.targetOrganizationId()))
            .toList();

    if (connected.size() != view.edges().size()) {
      log.error(
          "Dropped {} dangling edge(s) from the snapshot of session {}: the node and edge"
              + " visibility rules disagree.",
          view.edges().size() - connected.size(),
          view.session().slug());
      return new GraphView(view.session(), view.nodes(), connected);
    }
    return view;
  }
}
