package eu.sovereigntylens.domain.model;

/**
 * A live demo run.
 *
 * <p>{@code currentRound} is the pivot of the whole demo: it decides which audience contributions
 * are visible, and advancing it clears the canvas without deleting anything.
 */
public record Session(
    String id,
    String slug,
    String title,
    SessionStatus status,
    int currentRound,
    String rootOrganizationId) {}
