package eu.sovereigntylens.domain.model;

import java.util.UUID;

/**
 * A fully resolved contribution, ready to be persisted atomically.
 *
 * <p>Everything the persistence layer needs is decided before it is reached: the display name and
 * its comparison key are computed by {@code domain.service.Normalizer}, the contributor hash by the
 * application layer, and the quota by configuration. The store therefore only enforces invariants
 * that need a lock - the round quota, one contribution per browser, and duplicate edges.
 *
 * @param targetComparisonKey the deduplication key; two submissions sharing it name one organization
 * @param contributorHash keyed digest of the browser identifier, never the identifier itself
 * @param roundCapacity maximum active audience dependencies allowed in the current round
 */
public record DependencySubmission(
    String sessionSlug,
    UUID sourceOrganizationId,
    String targetDisplayName,
    String targetComparisonKey,
    OrganizationType targetOrganizationType,
    Jurisdiction targetJurisdiction,
    String contributorHash,
    int roundCapacity) {}
