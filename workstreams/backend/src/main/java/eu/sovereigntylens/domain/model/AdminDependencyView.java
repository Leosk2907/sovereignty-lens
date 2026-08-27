package eu.sovereigntylens.domain.model;

/**
 * One audience dependency expanded with both of its endpoints.
 *
 * <p>The presenter review list is the one place that shows an edge as a sentence - "X depends on
 * Y" - so it needs the organizations, not just their identifiers. Resolving them in the query that
 * reads the edges avoids a lookup per row while the presenter is on stage.
 */
public record AdminDependencyView(Dependency edge, Organization source, Organization target) {}
