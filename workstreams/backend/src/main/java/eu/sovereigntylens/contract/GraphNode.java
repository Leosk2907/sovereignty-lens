package eu.sovereigntylens.contract;

/** An organization rendered as a graph node. */
public record GraphNode(
    String id,
    String name,
    OrganizationType organizationType,
    Jurisdiction jurisdiction,
    boolean isSeed) {}
