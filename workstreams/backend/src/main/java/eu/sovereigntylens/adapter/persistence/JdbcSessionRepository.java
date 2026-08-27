package eu.sovereigntylens.adapter.persistence;

import eu.sovereigntylens.adapter.persistence.mapper.SessionRowMapper;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.port.SessionRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC implementation of the session port.
 *
 * <p>Uuid columns are cast to text in the select list because every id in the domain and on the
 * wire is a string; converting once here keeps {@code UUID} out of the layers above.
 */
@Repository
public class JdbcSessionRepository implements SessionRepository {

  private static final String SELECT_BY_SLUG =
      """
      select id::text as id, slug, title, status, current_round,
             root_organization_id::text as root_organization_id
      from sessions
      where slug = :slug
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcSessionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Session> findBySlug(String slug) {
    return jdbc.query(SELECT_BY_SLUG, Map.of("slug", slug), SessionRowMapper.INSTANCE).stream()
        .findFirst();
  }
}
