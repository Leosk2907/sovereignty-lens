package eu.sovereigntylens.domain.model;

import java.util.List;

/** Persisted result of an atomic company-profile contribution. */
public record CompanyProfileResult(
    int round,
    Organization company,
    List<Connection> customerConnections,
    List<Connection> dependencyConnections) {

  public CompanyProfileResult {
    customerConnections = List.copyOf(customerConnections);
    dependencyConnections = List.copyOf(dependencyConnections);
  }

  public record Connection(String eventId, Organization node, Dependency edge) {}
}
