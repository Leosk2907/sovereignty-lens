package eu.sovereigntylens.adapter.persistence;

import eu.sovereigntylens.adapter.persistence.mapper.DependencyRowMapper;
import eu.sovereigntylens.adapter.persistence.mapper.OrganizationRowMapper;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.port.GraphRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC implementation of the public graph port.
 *
 * <p>Visibility is decided here, in SQL, rather than by filtering in Java: a hidden dependency or a
 * previous round's contribution must never leave the database on a public read, and one
 * authoritative {@code where} clause is easier to audit than a chain of stream filters.
 *
 * <p>Uuid columns are cast to text in the select list so the row mappers can read every id with
 * {@code getString}, matching the string ids the contract defines.
 */
@Repository
public class JdbcGraphRepository implements GraphRepository {

  /**
   * Seed dependencies carry a null round and stay visible in every round; audience dependencies
   * belong to exactly one round and are visible only while that round is current. Hidden rows drop
   * out in both cases, which is what makes an admin "hide" take effect on the next read.
   */
  /**
   * Joins the predicate onto the queries below.
   *
   * <p>A text block that ends immediately after {@code where} contributes no trailing newline, so
   * concatenating directly produced {@code wheresession_id} and every graph read failed with a
   * syntax error. The separator is explicit rather than implied by the source layout.
   */
  private static final String NEWLINE = "\n";

  private static final String VISIBLE_EDGES_PREDICATE =
      """
      session_id = cast(:sessionId as uuid)
        and status = 'active'
        and ((is_seed and round is null) or (not is_seed and round = :round))
      """;

  private static final String SELECT_EDGES =
      """
      select id::text as id,
             source_organization_id::text as source_organization_id,
             target_organization_id::text as target_organization_id,
             is_seed,
             status,
             created_at
      from dependencies
      where """
          + NEWLINE
          + VISIBLE_EDGES_PREDICATE
          + """
      order by created_at, id
      """;

  /**
   * The root organization is selected unconditionally: at the start of a round it has no visible
   * edges, and a presentation that opens on an empty canvas instead of the root node reads as
   * broken. Organizations that exist but are not incident to a visible edge stay out.
   */
  private static final String SELECT_NODES =
      """
      with visible_edges as (
          select source_organization_id, target_organization_id
          from dependencies
          where """
          + NEWLINE
          + VISIBLE_EDGES_PREDICATE
          + """
      )
      select o.id::text as id,
             o.name,
             o.organization_type,
             o.jurisdiction,
             o.is_seed
      from organizations o
      where o.session_id = cast(:sessionId as uuid)
        and (o.id = cast(:rootOrganizationId as uuid)
             or o.id in (select source_organization_id from visible_edges)
             or o.id in (select target_organization_id from visible_edges))
      order by o.created_at, o.id
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcGraphRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Organization> findVisibleNodes(Session session) {
    Map<String, Object> parameters = visibilityParameters(session);
    parameters.put("rootOrganizationId", session.rootOrganizationId());
    return jdbc.query(SELECT_NODES, parameters, OrganizationRowMapper.INSTANCE);
  }

  @Override
  public List<Dependency> findVisibleEdges(Session session) {
    return jdbc.query(SELECT_EDGES, visibilityParameters(session), DependencyRowMapper.INSTANCE);
  }

  /** A mutable map, because the node query adds the root organization to these. */
  private Map<String, Object> visibilityParameters(Session session) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("sessionId", session.id());
    parameters.put("round", session.currentRound());
    return parameters;
  }
}
