package eu.sovereigntylens.contract;

/** Result of a presenter action, including the resulting authoritative session state. */
public record AdminActionResult(int contractVersion, String eventId, SessionSummary session) {

  public static AdminActionResult of(String eventId, SessionSummary session) {
    return new AdminActionResult(ContractVersion.CURRENT, eventId, session);
  }
}
