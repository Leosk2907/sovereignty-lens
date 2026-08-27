package eu.sovereigntylens.domain.model;

/**
 * Business failures the domain can report.
 *
 * <p>Deliberately free of transport knowledge: nothing here mentions HTTP statuses or the wire
 * contract. {@code eu.sovereigntylens.mapper.ErrorMapper} is the single place that decides how a
 * domain failure surfaces to a client, which is what lets the published contract and the business
 * rules be versioned independently.
 */
public enum DomainErrorCode {
  VALIDATION_ERROR,
  UNAUTHORIZED,
  FORBIDDEN,
  SESSION_NOT_FOUND,
  SOURCE_NOT_FOUND,
  NOT_FOUND,
  DUPLICATE_DEPENDENCY,
  ALREADY_CONTRIBUTED,
  SESSION_PAUSED,
  ROUND_CAPACITY_REACHED,
  INTERNAL_ERROR
}
