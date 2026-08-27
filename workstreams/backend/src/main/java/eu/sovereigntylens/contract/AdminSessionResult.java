package eu.sovereigntylens.contract;

/** Authenticated presenter session probe. */
public record AdminSessionResult(
    int contractVersion, boolean authenticated, SessionSummary session) {

  public static AdminSessionResult authenticated(SessionSummary session) {
    return new AdminSessionResult(ContractVersion.CURRENT, true, session);
  }
}
