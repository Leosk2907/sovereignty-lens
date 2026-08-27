package eu.sovereigntylens.contract;

import java.time.Instant;

/**
 * A dependency rendered as a directed graph edge.
 *
 * <p>An edge always means {@code source organization depends on target organization}. Reversing
 * that direction is a breaking contract change.
 */
public record GraphEdge(
    String id,
    String sourceOrganizationId,
    String targetOrganizationId,
    boolean isSeed,
    DependencyStatus status,
    Instant createdAt) {}
