package eu.sovereigntylens.domain.model;

import java.util.List;

/**
 * The public graph of one session at one moment.
 *
 * <p>Invariant: every edge references organizations present in {@code nodes}. A view that breaks it
 * would render as a floating edge, so it is enforced where the view is assembled rather than left
 * to the client.
 */
public record GraphView(Session session, List<Organization> nodes, List<Dependency> edges) {

  public GraphView {
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
  }
}
