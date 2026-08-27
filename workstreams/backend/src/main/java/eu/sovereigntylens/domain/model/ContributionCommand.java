package eu.sovereigntylens.domain.model;

/**
 * What an audience member asked for, before the application layer resolves it.
 *
 * <p>This is the raw request in domain terms; {@link DependencySubmission} is what it becomes once
 * the name has been normalized, the client identifier hashed and the round quota resolved. Keeping
 * the two apart is what stops the web adapter from having to know the hashing secret or the
 * deduplication rule.
 */
public record ContributionCommand(
    String sessionSlug,
    String anonymousClientId,
    String sourceOrganizationId,
    String targetName,
    OrganizationType targetOrganizationType,
    Jurisdiction targetJurisdiction) {}
