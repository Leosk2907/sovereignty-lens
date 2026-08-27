package eu.sovereigntylens.contract;

/** Authoritative presenter-visible session state. */
public record SessionSummary(
    String id,
    String slug,
    String title,
    SessionStatus status,
    int currentRound,
    String rootOrganizationId) {}
