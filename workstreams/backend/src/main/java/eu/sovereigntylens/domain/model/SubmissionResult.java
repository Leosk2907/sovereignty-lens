package eu.sovereigntylens.domain.model;

/**
 * What a committed contribution produced.
 *
 * <p>{@code eventId} is the identity of the live event emitted in the same transaction, so a client
 * that receives both the HTTP response and the broadcast can recognise them as one fact and apply
 * it once.
 *
 * @param targetNode the organization the dependency now points at, whether newly created or reused
 */
public record SubmissionResult(String eventId, int round, Organization targetNode, Dependency edge) {}
