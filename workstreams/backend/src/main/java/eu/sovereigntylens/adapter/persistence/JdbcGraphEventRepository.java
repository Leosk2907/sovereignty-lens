package eu.sovereigntylens.adapter.persistence;

import eu.sovereigntylens.domain.model.StoredGraphEvent;
import eu.sovereigntylens.domain.port.GraphEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link GraphEventRepository}.
 *
 * <p>{@code payload} is selected as text rather than through a JSON binding so the canonical bytes
 * the writing transaction produced reach the browser untouched.
 */
@Repository
public class JdbcGraphEventRepository implements GraphEventRepository {

  private static final String COLUMNS =
      """
      id::text as event_id, sequence, session_slug, round, event_type,
      payload::text as payload_json, occurred_at
      """;

  private static final String SELECT_BY_ID =
      """
      select %s
      from graph_events
      where id = :eventId::uuid
      """
          .formatted(COLUMNS);

  private static final String SELECT_AFTER_SEQUENCE =
      """
      select %s
      from graph_events
      where session_slug = :slug
        and sequence > :sequence
      order by sequence
      limit :limit
      """
          .formatted(COLUMNS);

  private static final String SELECT_SEQUENCE_BY_ID =
      """
      select sequence
      from graph_events
      where id = :eventId::uuid
      """;

  private static final String SELECT_LATEST_SEQUENCE =
      """
      select coalesce(max(sequence), 0)
      from graph_events
      where session_slug = :slug
      """;

  private static final RowMapper<StoredGraphEvent> EVENT =
      (rs, rowNumber) ->
          new StoredGraphEvent(
              rs.getString("event_id"),
              rs.getLong("sequence"),
              rs.getString("session_slug"),
              rs.getInt("round"),
              rs.getString("event_type"),
              rs.getString("payload_json"),
              rs.getObject("occurred_at", OffsetDateTime.class).toInstant());

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcGraphEventRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StoredGraphEvent> findByEventId(String eventId) {
    if (!isUuid(eventId)) {
      return Optional.empty();
    }
    return jdbc.query(SELECT_BY_ID, Map.of("eventId", eventId), EVENT).stream().findFirst();
  }

  @Override
  public List<StoredGraphEvent> findAfterSequence(String sessionSlug, long sequence, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return jdbc.query(
        SELECT_AFTER_SEQUENCE,
        Map.of("slug", sessionSlug, "sequence", sequence, "limit", limit),
        EVENT);
  }

  @Override
  public Optional<Long> findSequenceByEventId(String eventId) {
    if (!isUuid(eventId)) {
      return Optional.empty();
    }
    return jdbc
        .query(SELECT_SEQUENCE_BY_ID, Map.of("eventId", eventId), (rs, rowNumber) -> rs.getLong(1))
        .stream()
        .findFirst();
  }

  @Override
  public long latestSequence(String sessionSlug) {
    Long latest =
        jdbc.queryForObject(SELECT_LATEST_SEQUENCE, Map.of("slug", sessionSlug), Long.class);
    return latest == null ? 0L : latest;
  }

  /**
   * Event ids arrive from an untrusted {@code Last-Event-ID} request header. Rejecting a non-UUID
   * here keeps a malformed resume attempt a plain cache miss instead of a Postgres cast failure
   * surfacing as a 500 on what is meant to be a best-effort optimisation.
   */
  private static boolean isUuid(String candidate) {
    if (candidate == null || candidate.length() != 36) {
      return false;
    }
    try {
      return UUID.fromString(candidate).toString().equalsIgnoreCase(candidate);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
