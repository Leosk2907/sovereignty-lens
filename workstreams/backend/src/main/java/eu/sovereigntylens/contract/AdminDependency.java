package eu.sovereigntylens.contract;

/** One audience dependency expanded with both endpoints for presenter review. */
public record AdminDependency(GraphEdge edge, GraphNode source, GraphNode target) {}
