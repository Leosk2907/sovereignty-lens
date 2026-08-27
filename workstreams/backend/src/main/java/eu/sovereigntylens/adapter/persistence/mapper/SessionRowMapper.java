package eu.sovereigntylens.adapter.persistence.mapper;

import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.model.SessionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/** Turns a {@code sessions} row into a domain {@link Session}. */
public final class SessionRowMapper implements RowMapper<Session> {

  public static final SessionRowMapper INSTANCE = new SessionRowMapper();

  private final String prefix;

  public SessionRowMapper() {
    this("");
  }

  public SessionRowMapper(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Session mapRow(ResultSet rs, int rowNum) throws SQLException {
    return map(rs, prefix);
  }

  /** Maps a row whose session columns carry {@code prefix}. */
  public static Session map(ResultSet rs, String prefix) throws SQLException {
    return new Session(
        rs.getString(prefix + "id"),
        rs.getString(prefix + "slug"),
        rs.getString(prefix + "title"),
        SessionStatus.fromWire(rs.getString(prefix + "status")),
        rs.getInt(prefix + "current_round"),
        rs.getString(prefix + "root_organization_id"));
  }
}
