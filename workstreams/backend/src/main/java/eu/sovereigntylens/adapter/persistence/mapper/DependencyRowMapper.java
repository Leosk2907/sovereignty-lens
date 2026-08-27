package eu.sovereigntylens.adapter.persistence.mapper;

import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.DependencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.RowMapper;

/**
 * Turns a {@code dependencies} row into a domain {@link Dependency}.
 *
 * <p>{@code contributor_hash} and {@code round} are deliberately not mapped: the hash must never
 * leave the database, and the round is a visibility filter rather than a property of the edge.
 */
public final class DependencyRowMapper implements RowMapper<Dependency> {

  public static final DependencyRowMapper INSTANCE = new DependencyRowMapper();

  private final String prefix;

  public DependencyRowMapper() {
    this("");
  }

  public DependencyRowMapper(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Dependency mapRow(ResultSet rs, int rowNum) throws SQLException {
    return map(rs, prefix);
  }

  /** Maps a row whose dependency columns carry {@code prefix}. */
  public static Dependency map(ResultSet rs, String prefix) throws SQLException {
    Timestamp createdAt = rs.getTimestamp(prefix + "created_at");
    return new Dependency(
        rs.getString(prefix + "id"),
        rs.getString(prefix + "source_organization_id"),
        rs.getString(prefix + "target_organization_id"),
        rs.getBoolean(prefix + "is_seed"),
        DependencyStatus.fromWire(rs.getString(prefix + "status")),
        createdAt.toInstant());
  }
}
