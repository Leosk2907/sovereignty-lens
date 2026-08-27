package eu.sovereigntylens.contract;

/**
 * Canonical persisted result of a successful contribution.
 *
 * <p>{@code eventId}, {@code node.id} and {@code edge.id} exactly match the corresponding live
 * event, so a client that receives both applies them idempotently.
 */
public record ContributionResult(
    int contractVersion, String eventId, int round, GraphNode node, GraphEdge edge) {

  public static ContributionResult of(String eventId, int round, GraphNode node, GraphEdge edge) {
    return new ContributionResult(ContractVersion.CURRENT, eventId, round, node, edge);
  }
}
