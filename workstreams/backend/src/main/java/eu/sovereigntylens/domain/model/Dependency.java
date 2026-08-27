package eu.sovereigntylens.domain.model;

import java.time.Instant;

/**
 * A directed edge meaning "source depends on target". Reversing that direction would invert the
 * story the graph tells, so it is fixed by the data contract.
 *
 * <p>The contributor hash and the round are omitted: both are internal bookkeeping, and the hash in
 * particular must never leave the database.
 */
public record Dependency(
    String id,
    String sourceOrganizationId,
    String targetOrganizationId,
    boolean seed,
    DependencyStatus status,
    Instant createdAt) {}
