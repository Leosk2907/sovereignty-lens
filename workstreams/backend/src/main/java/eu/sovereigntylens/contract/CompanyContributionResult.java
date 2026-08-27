package eu.sovereigntylens.contract;

import java.util.List;

/** Canonical result of one committed company-profile batch. */
public record CompanyContributionResult(
    int contractVersion,
    int round,
    GraphNode company,
    List<CompanyContributionConnection> customerConnections,
    List<CompanyContributionConnection> dependencyConnections) {

  public static CompanyContributionResult of(
      int round,
      GraphNode company,
      List<CompanyContributionConnection> customerConnections,
      List<CompanyContributionConnection> dependencyConnections) {
    return new CompanyContributionResult(
        ContractVersion.CURRENT,
        round,
        company,
        List.copyOf(customerConnections),
        List.copyOf(dependencyConnections));
  }
}
