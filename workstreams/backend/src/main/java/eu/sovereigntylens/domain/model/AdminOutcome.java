package eu.sovereigntylens.domain.model;

/**
 * What a presenter action produced.
 *
 * <p>Carries the authoritative session as it stands after the action rather than as the presenter
 * assumed it to be, so the admin screen never has to guess whether its own optimistic update was
 * the one that won.
 *
 * @param eventId identity of the {@code graph.invalidated} event emitted in the same transaction;
 *     a client that also receives it over the live stream can recognise the two as one fact
 */
public record AdminOutcome(String eventId, Session session) {}
