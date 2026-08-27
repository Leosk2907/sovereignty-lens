package eu.sovereigntylens.contract;

import java.util.List;

/** All current-round, non-seed dependencies including hidden entries, newest first. */
public record AdminDependencyList(
    int contractVersion, SessionSummary session, List<AdminDependency> dependencies) {

  public static AdminDependencyList of(SessionSummary session, List<AdminDependency> dependencies) {
    return new AdminDependencyList(ContractVersion.CURRENT, session, dependencies);
  }
}
