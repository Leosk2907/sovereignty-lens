package eu.sovereigntylens.contract;

/** Result of a hide/restore action. */
public record DependencyStatusResult(int contractVersion, String eventId, GraphEdge edge) {

  public static DependencyStatusResult of(String eventId, GraphEdge edge) {
    return new DependencyStatusResult(ContractVersion.CURRENT, eventId, edge);
  }
}
