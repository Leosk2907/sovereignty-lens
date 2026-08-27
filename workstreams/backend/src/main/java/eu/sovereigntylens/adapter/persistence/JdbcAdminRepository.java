package eu.sovereigntylens.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.AdminDependencyView;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.model.SessionStatus;
import eu.sovereigntylens.adapter.persistence.mapper.DependencyRowMapper;
import eu.sovereigntylens.adapter.persistence.mapper.OrganizationRowMapper;
import eu.sovereigntylens.adapter.persistence.mapper.SessionRowMapper;
import eu.sovereigntylens.domain.port.AdminRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link AdminRepository}.
 *
 * <p>Two rules shape every statement here. First, no presenter action deletes anything: pause and
 * resume move a flag, undo and hide move a row's status, and reset moves the round marker forward
 * so earlier rounds fall out of the current-round filter while staying on disk. Second, each action
 * emits its {@code graph.invalidated} event through {@code emit_graph_event} inside the caller's
 * transaction, so a rollback discards the announcement exactly as it discards the change.
 *
 * <p>Every mutation takes the session row lock first - the same lock {@code submit_dependency}
 * takes - so an admin action and a contribution arriving together are ordered rather than
 * interleaved. That order is a rule, not a habit: it is what keeps event sequence numbers
 * consistent with commit order, and taking any two rows in the opposite order is how a transaction
 * here would deadlock against a concurrent one.
 */
@Repository
public class JdbcAdminRepository implements AdminRepository {

  private static final String SESSION_COLUMNS =
      "id::text as id, slug, title, status, current_round, "
          + "root_organization_id::text as root_organization_id";

  private static final String LOCK_SESSION =
      """
      select %s
      from sessions
      where slug = :slug
      for update
      """
          .formatted(SESSION_COLUMNS);

  // The session row that owns one dependency, locked before the dependency row itself. Written as
  // a subquery rather than a join so that "for update" touches sessions and nothing else.
  private static final String LOCK_SESSION_OF_DEPENDENCY =
      """
      select %s
      from sessions
      where id = (select session_id from dependencies where id = :id::uuid)
      for update
      """
          .formatted(SESSION_COLUMNS);

  private static final String READ_SESSION =
      """
      select %s
      from sessions
      where slug = :slug
      """
          .formatted(SESSION_COLUMNS);

  // Unconditional assignment: pausing an already paused session is a presenter clicking twice, not
  // a conflict, and the returning clause still reports the authoritative state either way.
  private static final String SET_SESSION_STATUS =
      """
      update sessions
      set status = :status
      where slug = :slug
      returning %s
      """
          .formatted(SESSION_COLUMNS);

  // Reset never deletes: the previous round's rows stay exactly where they are and simply stop
  // matching the current-round filter that the public graph and the admin list both use.
  private static final String START_NEXT_ROUND =
      """
      update sessions
      set current_round = current_round + 1,
          status = 'open'
      where slug = :slug
      returning %s
      """
          .formatted(SESSION_COLUMNS);

  private static final String HIDE_NEWEST_ACTIVE =
      """
      update dependencies
      set status = 'hidden'
      where id = (select d.id
                  from dependencies d
                           join sessions s on s.id = d.session_id
                  where s.slug = :slug
                    and d.round = s.current_round
                    and not d.is_seed
                    and d.status = 'active'
                  order by d.created_at desc, d.id desc
                  limit 1)
      returning id::text as id
      """;

  private static final String LOCK_DEPENDENCY =
      """
      select d.id::text                     as id,
             d.round                        as round,
             d.is_seed                      as is_seed,
             d.status                       as status,
             d.source_organization_id::text as source_organization_id,
             d.target_organization_id::text as target_organization_id,
             s.id::text                     as session_id,
             s.slug                         as session_slug,
             s.current_round                as current_round
      from dependencies d
               join sessions s on s.id = d.session_id
      where d.id = :id::uuid
      for update of d
      """;

  // Mirrors the partial unique index dependencies_active_edge_key exactly, including its
  // coalesce(round, 0) bucket, so the pre-check and the constraint can never disagree.
  private static final String ACTIVE_DUPLICATE_EXISTS =
      """
      select exists (select 1
                     from dependencies
                     where session_id = :sessionId::uuid
                       and coalesce(round, 0) = :round
                       and source_organization_id = :sourceId::uuid
                       and target_organization_id = :targetId::uuid
                       and status = 'active'
                       and id <> :id::uuid)
      """;

  private static final String SET_DEPENDENCY_STATUS =
      """
      update dependencies
      set status = :status
      where id = :id::uuid
      returning id::text                     as id,
                source_organization_id::text as source_organization_id,
                target_organization_id::text as target_organization_id,
                is_seed                      as is_seed,
                status                       as status,
                created_at                   as created_at
      """;

  private static final String LIST_CURRENT_ROUND =
      """
      select d.id::text                     as d_id,
             d.source_organization_id::text as d_source_organization_id,
             d.target_organization_id::text as d_target_organization_id,
             d.is_seed                      as d_is_seed,
             d.status                       as d_status,
             d.created_at                   as d_created_at,
             src.id::text                   as src_id,
             src.name                       as src_name,
             src.organization_type          as src_organization_type,
             src.jurisdiction               as src_jurisdiction,
             src.is_seed                    as src_is_seed,
             tgt.id::text                   as tgt_id,
             tgt.name                       as tgt_name,
             tgt.organization_type          as tgt_organization_type,
             tgt.jurisdiction               as tgt_jurisdiction,
             tgt.is_seed                    as tgt_is_seed
      from dependencies d
               join sessions s on s.id = d.session_id
               join organizations src on src.id = d.source_organization_id
               join organizations tgt on tgt.id = d.target_organization_id
      where s.slug = :slug
        and d.round = s.current_round
        and not d.is_seed
      order by d.created_at desc, d.id desc
      """;

  private static final String EMIT_INVALIDATION =
      """
      select emit_graph_event(
          :sessionId::uuid,
          :slug,
          :round,
          'graph.invalidated',
          jsonb_build_object('reason', :reason))
      """;

  // Delegates to the shared mapper so the domain model has exactly one
  // translation from a session row, whichever slice reads it.
  private static final RowMapper<Session> SESSION_MAPPER = SessionRowMapper.INSTANCE;

  private static final RowMapper<Dependency> DEPENDENCY_MAPPER =
      (ResultSet rs, int rowNum) -> dependency(rs, "");

  private static final RowMapper<AdminDependencyView> VIEW_MAPPER =
      (ResultSet rs, int rowNum) ->
          new AdminDependencyView(
              dependency(rs, "d_"), organization(rs, "src_"), organization(rs, "tgt_"));

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public AdminOutcome pause(String sessionSlug) {
    return applySessionStatus(sessionSlug, SessionStatus.PAUSED, Reason.PAUSE);
  }

  @Override
  public AdminOutcome resume(String sessionSlug) {
    return applySessionStatus(sessionSlug, SessionStatus.OPEN, Reason.RESUME);
  }

  @Override
  public AdminOutcome reset(String sessionSlug) {
    MapSqlParameterSource parameters = new MapSqlParameterSource("slug", sessionSlug);
    Session session =
        onlyRow(jdbc.query(START_NEXT_ROUND, parameters, SESSION_MAPPER), sessionSlug);
    // The returned row already carries the incremented round, so the event announces the round the
    // audience is being moved to rather than the one it is leaving.
    return new AdminOutcome(emit(session, Reason.RESET), session);
  }

  @Override
  public Optional<AdminOutcome> undo(String sessionSlug) {
    Session session = lockSession(sessionSlug);
    List<String> hidden =
        jdbc.query(
            HIDE_NEWEST_ACTIVE,
            new MapSqlParameterSource("slug", sessionSlug),
            (ResultSet rs, int rowNum) -> rs.getString("id"));
    if (hidden.isEmpty()) {
      // Nothing was written, so nothing is announced. Returning empty rather than a fabricated
      // outcome keeps "the presenter pressed undo once too often" distinguishable from a real undo.
      return Optional.empty();
    }
    return Optional.of(new AdminOutcome(emit(session, Reason.UNDO), session));
  }

  @Override
  public AdminOutcome invalidate(String sessionSlug, Reason reason) {
    Session session = requireSession(sessionSlug);
    return new AdminOutcome(emit(session, reason), session);
  }

  @Override
  public List<AdminDependencyView> listCurrentRoundDependencies(String sessionSlug) {
    // Read-only, so the slug is checked explicitly: an empty result for an unknown session would
    // otherwise be indistinguishable from a round nobody has contributed to yet.
    requireSession(sessionSlug);
    MapSqlParameterSource parameters = new MapSqlParameterSource("slug", sessionSlug);
    return jdbc.query(LIST_CURRENT_ROUND, parameters, VIEW_MAPPER);
  }

  @Override
  public DependencyOutcome setStatus(UUID dependencyId, DependencyStatus status) {
    // The session row is locked before the dependency row, which is the order every other writer
    // uses: submit_dependency locks the session first, and undo locks the session and then updates
    // a dependency row. Taking the two in the opposite order here was worth two defects.
    //
    // It let a hide or restore run concurrently with a contribution, and since graph_events.sequence
    // comes from a bigserial assigned at insert rather than at commit, the invalidation could be
    // handed a lower sequence and still commit later - after which the live stream suppressed it as
    // stale and the presenter's click did nothing on screen until the next poll.
    //
    // It also made a genuine lock cycle possible: undo holding the session row and waiting for a
    // dependency row, against setStatus holding that dependency row and waiting for the session,
    // which Postgres resolves by killing one of them with a 40P01.
    Session session = lockSessionOfDependency(dependencyId);
    Target target = lockTarget(dependencyId);
    if (status == DependencyStatus.ACTIVE) {
      rejectDuplicateRestore(dependencyId, target);
    }

    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", dependencyId.toString())
            .addValue("status", status.wireValue());
    Dependency dependency;
    try {
      dependency = onlyDependency(jdbc.query(SET_DEPENDENCY_STATUS, parameters, DEPENDENCY_MAPPER));
    } catch (DuplicateKeyException e) {
      // The pre-check above holds a row lock on this dependency but not on the edge that would
      // collide with it, so the partial unique index stays the real guarantee.
      throw duplicateRestore(e);
    }

    Reason reason = status == DependencyStatus.ACTIVE ? Reason.RESTORE : Reason.HIDE;
    return new DependencyOutcome(emit(session, reason), dependency);
  }

  private AdminOutcome applySessionStatus(String sessionSlug, SessionStatus status, Reason reason) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("slug", sessionSlug)
            .addValue("status", status.wireValue());
    Session session =
        onlyRow(jdbc.query(SET_SESSION_STATUS, parameters, SESSION_MAPPER), sessionSlug);
    return new AdminOutcome(emit(session, reason), session);
  }

  /**
   * Loads the dependency the presenter asked for and refuses everything a presenter may not touch.
   *
   * <p>A seed row or a row from an earlier round is reported as {@code NOT_FOUND} rather than as a
   * distinct failure: from the admin screen's point of view only the current round exists, and the
   * screen has no control that could produce any other outcome.
   */
  private Target lockTarget(UUID dependencyId) {
    Target target =
        jdbc
            .query(
                LOCK_DEPENDENCY,
                new MapSqlParameterSource("id", dependencyId.toString()),
                (ResultSet rs, int rowNum) ->
                    new Target(
                        rs.getString("session_id"),
                        rs.getString("session_slug"),
                        rs.getInt("current_round"),
                        (Integer) rs.getObject("round"),
                        rs.getBoolean("is_seed"),
                        rs.getString("source_organization_id"),
                        rs.getString("target_organization_id")))
            .stream()
            .findFirst()
            .orElseThrow(JdbcAdminRepository::noSuchDependency);
    if (target.seed() || target.round() == null || target.round() != target.currentRound()) {
      throw noSuchDependency();
    }
    return target;
  }

  private void rejectDuplicateRestore(UUID dependencyId, Target target) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("sessionId", target.sessionId())
            .addValue("round", target.currentRound())
            .addValue("sourceId", target.sourceOrganizationId())
            .addValue("targetId", target.targetOrganizationId())
            .addValue("id", dependencyId.toString());
    Boolean conflicting =
        jdbc.queryForObject(ACTIVE_DUPLICATE_EXISTS, parameters, Boolean.class);
    if (Boolean.TRUE.equals(conflicting)) {
      throw duplicateRestore(null);
    }
  }

  private static DomainException duplicateRestore(Throwable cause) {
    return new DomainException(
        DomainErrorCode.DUPLICATE_DEPENDENCY,
        "That dependency is already active in this round.",
        null,
        cause);
  }

  private static DomainException noSuchDependency() {
    return DomainException.notFound("No such dependency in the current round.");
  }

  private String emit(Session session, Reason reason) {
    return emit(session.id(), session.slug(), session.currentRound(), reason);
  }

  /**
   * Writes the durable event row and wakes the live stream, inside the caller's transaction.
   *
   * <p>The function returns the whole canonical event; only its identity is needed here, because
   * the payload a client eventually receives comes from the stored row rather than from this call.
   */
  private String emit(String sessionId, String slug, int round, Reason reason) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("sessionId", sessionId)
            .addValue("slug", slug)
            .addValue("round", round)
            .addValue("reason", reason.wireValue());
    String event = jdbc.queryForObject(EMIT_INVALIDATION, parameters, String.class);
    return eventId(event);
  }

  private String eventId(String event) {
    try {
      JsonNode parsed = objectMapper.readTree(event == null ? "{}" : event);
      String eventId = parsed.path("eventId").asText(null);
      if (eventId == null || eventId.isBlank()) {
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR, "The service could not record the change.");
      }
      return eventId;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "The service could not record the change.", null, e);
    }
  }

  private Session lockSession(String sessionSlug) {
    return session(LOCK_SESSION, sessionSlug);
  }

  /**
   * Locks the session that owns a dependency, before anything looks at the dependency itself.
   *
   * <p>An empty result means the id names no row at all, which is reported the same way {@link
   * #lockTarget} reports a seed row or a stale round: the admin screen knows only the current round
   * and has no control that could produce a different outcome.
   */
  private Session lockSessionOfDependency(UUID dependencyId) {
    return jdbc
        .query(
            LOCK_SESSION_OF_DEPENDENCY,
            new MapSqlParameterSource("id", dependencyId.toString()),
            SESSION_MAPPER)
        .stream()
        .findFirst()
        .orElseThrow(JdbcAdminRepository::noSuchDependency);
  }

  private Session requireSession(String sessionSlug) {
    return session(READ_SESSION, sessionSlug);
  }

  private Session session(String sql, String sessionSlug) {
    return jdbc.query(sql, new MapSqlParameterSource("slug", sessionSlug), SESSION_MAPPER).stream()
        .findFirst()
        .orElseThrow(() -> DomainException.sessionNotFound(sessionSlug));
  }

  private static Session onlyRow(List<Session> rows, String sessionSlug) {
    return rows.stream()
        .findFirst()
        .orElseThrow(() -> DomainException.sessionNotFound(sessionSlug));
  }

  private static Dependency onlyDependency(List<Dependency> rows) {
    return rows.stream().findFirst().orElseThrow(JdbcAdminRepository::noSuchDependency);
  }

  private static Dependency dependency(ResultSet rs, String prefix) throws SQLException {
    return DependencyRowMapper.map(rs, prefix);
  }

  private static Organization organization(ResultSet rs, String prefix) throws SQLException {
    return OrganizationRowMapper.map(rs, prefix);
  }

  private static UUID uuidOrNull(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  /** The parts of a dependency's row that decide whether a presenter may change it. */
  private record Target(
      String sessionId,
      String sessionSlug,
      int currentRound,
      Integer round,
      boolean seed,
      String sourceOrganizationId,
      String targetOrganizationId) {}
}
