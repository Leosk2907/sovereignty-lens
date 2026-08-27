package eu.sovereigntylens.adapter.persistence;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/**
 * Translates the custom {@code SL###} SQLSTATEs raised by {@code submit_dependency} into
 * {@link DomainException}s.
 *
 * <p>The database is the only place where the session lock, the round quota and the duplicate
 * checks can be evaluated atomically, so it is also the only thing that can say which of them
 * failed. This class is the one-way door back into domain terms; Spring's own SQLSTATE translation
 * knows nothing about the {@code SL} class and would surface these as generic data access failures.
 *
 * <p>It lives in the persistence adapter rather than the domain because a SQLSTATE is a detail of
 * one particular store. The messages, by contrast, are audience-facing: the database's own
 * {@code SQLERRM} text never reaches a response, because it carries raw SQL, identifiers and
 * parameter values.
 */
public final class SqlStateErrors {

  private static final String SL_CLASS_PREFIX = "SL";

  private SqlStateErrors() {}

  /**
   * Maps a Spring data access failure onto a domain failure.
   *
   * @return the mapped exception, or empty when the underlying SQLSTATE is not one of ours, in
   *     which case the caller must rethrow the original rather than guess at a business meaning
   */
  public static Optional<DomainException> translate(DataAccessException exception) {
    return translate((Throwable) exception);
  }

  /** Maps a raw JDBC failure onto a domain failure. See {@link #translate(DataAccessException)}. */
  public static Optional<DomainException> translate(SQLException exception) {
    return translate((Throwable) exception);
  }

  private static Optional<DomainException> translate(Throwable throwable) {
    return sqlState(throwable).flatMap(state -> forSqlState(state, throwable));
  }

  private static Optional<DomainException> forSqlState(String sqlState, Throwable cause) {
    return switch (sqlState) {
      case "SL001" ->
          mapped(DomainErrorCode.SESSION_NOT_FOUND, "This session no longer exists.", null, cause);
      case "SL002" ->
          mapped(
              DomainErrorCode.SOURCE_NOT_FOUND,
              "The selected organization is not part of this session.",
              "sourceOrganizationId",
              cause);
      case "SL003" ->
          mapped(
              DomainErrorCode.SESSION_PAUSED,
              "This session is paused and is not accepting contributions.",
              null,
              cause);
      case "SL004" ->
          mapped(
              DomainErrorCode.ALREADY_CONTRIBUTED,
              "You have already contributed in this round.",
              null,
              cause);
      case "SL005" ->
          mapped(
              DomainErrorCode.DUPLICATE_DEPENDENCY,
              "This dependency has already been added in this round.",
              null,
              cause);
      case "SL006" ->
          mapped(
              DomainErrorCode.ROUND_CAPACITY_REACHED,
              "This round is full. Wait for the presenter to open the next one.",
              null,
              cause);
      // SL007 covers the invariants the function can only check once it holds the row - in practice
      // a self-referencing dependency - so the target name is the field at fault. The government
      // rule it also guards is rejected earlier, by the use case.
      case "SL007" ->
          mapped(
              DomainErrorCode.VALIDATION_ERROR,
              "That dependency is not allowed for the selected organization.",
              "target.name",
              cause);
      default -> Optional.empty();
    };
  }

  private static Optional<DomainException> mapped(
      DomainErrorCode code, String message, String field, Throwable cause) {
    return Optional.of(new DomainException(code, message, field, cause));
  }

  /**
   * The driver's {@link SQLException} sits several wrappers deep inside a Spring exception, and can
   * itself chain further failures through {@code getNextException}, so both chains are walked.
   */
  private static Optional<String> sqlState(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        for (SQLException link = sqlException; link != null; link = link.getNextException()) {
          String state = link.getSQLState();
          if (state != null && state.startsWith(SL_CLASS_PREFIX)) {
            return Optional.of(state);
          }
        }
      }
      // A self-referential cause would otherwise spin here forever.
      if (current.getCause() == current) {
        break;
      }
    }
    return Optional.empty();
  }
}
