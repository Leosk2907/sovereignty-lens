package eu.sovereigntylens.adapter.persistence.mapper;

import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.OrganizationType;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/**
 * Turns an {@code organizations} row into a domain {@link Organization}.
 *
 * <p>Database-only columns - {@code normalized_name}, {@code session_id} - are never mapped, which
 * is what keeps them out of every payload built downstream.
 *
 * <p>The prefixed variant exists for joined queries that select organization and dependency columns
 * side by side and must alias them apart.
 */
public final class OrganizationRowMapper implements RowMapper<Organization> {

  public static final OrganizationRowMapper INSTANCE = new OrganizationRowMapper();

  private final String prefix;

  public OrganizationRowMapper() {
    this("");
  }

  public OrganizationRowMapper(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Organization mapRow(ResultSet rs, int rowNum) throws SQLException {
    return map(rs, prefix);
  }

  /** Maps a row whose organization columns carry {@code prefix}. */
  public static Organization map(ResultSet rs, String prefix) throws SQLException {
    return new Organization(
        rs.getString(prefix + "id"),
        rs.getString(prefix + "name"),
        OrganizationType.fromWire(rs.getString(prefix + "organization_type")),
        Jurisdiction.fromWire(rs.getString(prefix + "jurisdiction")),
        rs.getBoolean(prefix + "is_seed"));
  }
}
