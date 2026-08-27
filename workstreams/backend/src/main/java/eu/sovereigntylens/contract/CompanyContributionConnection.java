package eu.sovereigntylens.contract;

/** One canonical edge and its matching live-event identity. */
public record CompanyContributionConnection(String eventId, GraphNode node, GraphEdge edge) {}
