package eu.sovereigntylens.contract;

import java.time.Instant;
import java.util.List;

/**
 * Authoritative public graph read.
 *
 * <p>Contains seed edges plus active audience edges from the current round, and every node those
 * edges reference plus the session root.
 */
public record GraphSnapshot(
    int contractVersion,
    SessionSummary session,
    List<GraphNode> nodes,
    List<GraphEdge> edges,
    Instant serverTime) {

  public static GraphSnapshot of(
      SessionSummary session, List<GraphNode> nodes, List<GraphEdge> edges, Instant serverTime) {
    return new GraphSnapshot(ContractVersion.CURRENT, session, nodes, edges, serverTime);
  }
}
