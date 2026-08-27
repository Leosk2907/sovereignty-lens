package eu.sovereigntylens.domain.model;

/**
 * A company or public body that appears as a node of the dependency graph.
 *
 * <p>The normalized comparison key that deduplicates two audience members naming the same company
 * is deliberately absent: it is a persistence concern and must never reach a client.
 *
 * @param seed true for an organization planted before the session, false for one the audience named
 */
public record Organization(
    String id,
    String name,
    OrganizationType organizationType,
    Jurisdiction jurisdiction,
    boolean seed) {}
