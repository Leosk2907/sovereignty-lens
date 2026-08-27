package eu.sovereigntylens.support;

import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.service.Normalizer;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Builds and inspects rows for the integration suite.
 *
 * <p>Every fixture creates a <em>new</em> session with a generated slug rather than reusing the
 * {@code demo} session the seed migration installs. The container is shared by the whole suite, so
 * isolation has to come from the data: a test that mutated {@code demo} would silently change what
 * {@code FlywayMigrationIT} sees, and nothing here may write to it. Sessions accumulate for the
 * lifetime of the container and are never cleaned up, because nothing reads across sessions.
 *
 * <p>Writes go through plain SQL rather than through the production repositories: a fixture that
 * used the code under test to build its own preconditions could not fail independently of it.
 */
public final class DatabaseFixtures {

  private static final String INSERT_ORGANIZATION =
      """
      insert into organizations (id, session_id, name, normalized_name, organization_type,
                                 jurisdiction, is_seed, created_at)
      values (cast(:id as uuid), cast(:sessionId as uuid), :name, :normalizedName, :type,
              :jurisdiction, :seed, coalesce(cast(:createdAt as timestamptz), now()))
      """;

  private static final String INSERT_DEPENDENCY =
      """
      insert into dependencies (id, session_id, round, source_organization_id,
                                target_organization_id, contributor_hash, is_seed, status,
                                created_at)
      values (cast(:id as uuid), cast(:sessionId as uuid), :round, cast(:source as uuid),
              cast(:target as uuid), :contributorHash, :seed, :status,
              coalesce(cast(:createdAt as timestamptz), now()))
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public DatabaseFixtures(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A session whose only organization is its seed government root, with no dependencies at all.
   *
   * <p>The three supplier ids are null; use {@link #seededSession()} when a test needs them.
   */
  public SessionFixture bareSession() {
    String slug = newSlug();
    UUID sessionId = insertSession(slug);
    UUID root = insertOrganization(sessionId, "Root Agency", OrganizationType.GOVERNMENT,
        Jurisdiction.EUROPE, true);
    setRootOrganization(sessionId, root);
    return new SessionFixture(sessionId, slug, root, null, null, null, List.of());
  }

  /**
   * A session shaped exactly like the demo seed: a government root, three European seed suppliers
   * and three seed edges, so a test can reason about "seed rows stay visible" without depending on
   * the demo session it must not touch.
   */
  public SessionFixture seededSession() {
    String slug = newSlug();
    UUID sessionId = insertSession(slug);
    UUID root = insertOrganization(sessionId, "Root Agency", OrganizationType.GOVERNMENT,
        Jurisdiction.EUROPE, true);
    setRootOrganization(sessionId, root);
    UUID supplier = insertOrganization(sessionId, "Alpine Civic Systems", OrganizationType.SOFTWARE,
        Jurisdiction.EUROPE, true);
    UUID subSupplier = insertOrganization(sessionId, "Baltic Data Works", OrganizationType.CLOUD,
        Jurisdiction.EUROPE, true);
    UUID carrier = insertOrganization(sessionId, "Rhine Public Networks",
        OrganizationType.TELECOM, Jurisdiction.EUROPE, true);

    List<UUID> seedEdges =
        List.of(
            insertSeedDependency(sessionId, root, supplier),
            insertSeedDependency(sessionId, root, carrier),
            insertSeedDependency(sessionId, supplier, subSupplier));

    return new SessionFixture(sessionId, slug, root, supplier, subSupplier, carrier, seedEdges);
  }

  public String newSlug() {
    // Slug collisions across a shared container would make one test's failure look like another's.
    return "it-" + UUID.randomUUID();
  }

  public UUID insertSession(String slug) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into sessions (id, slug, title, status, current_round)
        values (cast(:id as uuid), :slug, :title, 'open', 1)
        """,
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("slug", slug)
            .addValue("title", "Integration session " + slug));
    return id;
  }

  public void setRootOrganization(UUID sessionId, UUID organizationId) {
    jdbc.update(
        "update sessions set root_organization_id = cast(:root as uuid)"
            + " where id = cast(:id as uuid)",
        Map.of("root", organizationId.toString(), "id", sessionId.toString()));
  }

  public UUID insertOrganization(
      UUID sessionId,
      String name,
      OrganizationType type,
      Jurisdiction jurisdiction,
      boolean seed) {
    return insertOrganization(UUID.randomUUID(), sessionId, name, type, jurisdiction, seed, null);
  }

  public UUID insertOrganization(
      UUID id,
      UUID sessionId,
      String name,
      OrganizationType type,
      Jurisdiction jurisdiction,
      boolean seed,
      Instant createdAt) {
    jdbc.update(
        INSERT_ORGANIZATION,
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("sessionId", sessionId.toString())
            .addValue("name", name)
            // The same normalizer the use case applies, so a fixture and a submission agree on
            // which two names are "the same company".
            .addValue("normalizedName", Normalizer.comparisonKey(name))
            .addValue("type", type.wireValue())
            .addValue("jurisdiction", jurisdiction.wireValue())
            .addValue("seed", seed)
            .addValue("createdAt", text(createdAt), Types.VARCHAR));
    return id;
  }

  public UUID insertSeedDependency(UUID sessionId, UUID source, UUID target) {
    return insertDependency(
        UUID.randomUUID(), sessionId, null, source, target, null, true, "active", null);
  }

  public UUID insertAudienceDependency(
      UUID sessionId, int round, UUID source, UUID target, String contributorHash) {
    return insertDependency(
        UUID.randomUUID(),
        sessionId,
        round,
        source,
        target,
        contributorHash,
        false,
        "active",
        null);
  }

  public UUID insertDependency(
      UUID id,
      UUID sessionId,
      Integer round,
      UUID source,
      UUID target,
      String contributorHash,
      boolean seed,
      String status,
      Instant createdAt) {
    jdbc.update(
        INSERT_DEPENDENCY,
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("sessionId", sessionId.toString())
            .addValue("round", round, Types.INTEGER)
            .addValue("source", source.toString())
            .addValue("target", target.toString())
            .addValue("contributorHash", contributorHash, Types.VARCHAR)
            .addValue("seed", seed)
            .addValue("status", status)
            .addValue("createdAt", text(createdAt), Types.VARCHAR));
    return id;
  }

  /** Builds the value {@code ContributionService} would hand the repository. */
  public static DependencySubmission submission(
      String sessionSlug,
      UUID sourceOrganizationId,
      String targetName,
      OrganizationType type,
      Jurisdiction jurisdiction,
      String contributorHash,
      int roundCapacity) {
    String displayName = Normalizer.displayName(targetName);
    return new DependencySubmission(
        sessionSlug,
        sourceOrganizationId,
        displayName,
        Normalizer.comparisonKey(displayName),
        type,
        jurisdiction,
        contributorHash,
        roundCapacity);
  }

  /**
   * A contributor hash for one browser.
   *
   * <p>Deterministic in {@code label}, so "the same browser" and "a different browser" are both
   * expressible. The column is opaque text to the database, and the uniqueness index is scoped to a
   * session and round, so labels may safely repeat across tests that use different sessions.
   */
  public static String contributorHash(String label) {
    return "contributor-" + label;
  }

  public void pauseSession(String slug) {
    jdbc.update("update sessions set status = 'paused' where slug = :slug", Map.of("slug", slug));
  }

  public void setDependencyStatus(UUID dependencyId, String status) {
    jdbc.update(
        "update dependencies set status = :status where id = cast(:id as uuid)",
        Map.of("status", status, "id", dependencyId.toString()));
  }

  public void setDependencyCreatedAt(UUID dependencyId, Instant createdAt) {
    jdbc.update(
        "update dependencies set created_at = cast(:createdAt as timestamptz)"
            + " where id = cast(:id as uuid)",
        Map.of("createdAt", createdAt.toString(), "id", dependencyId.toString()));
  }

  public String sessionStatus(String slug) {
    return jdbc.queryForObject(
        "select status from sessions where slug = :slug", Map.of("slug", slug), String.class);
  }

  public int currentRound(String slug) {
    Integer round =
        jdbc.queryForObject(
            "select current_round from sessions where slug = :slug",
            Map.of("slug", slug),
            Integer.class);
    return round == null ? 0 : round;
  }

  public List<StoredEvent> eventsOf(String sessionSlug) {
    return jdbc.query(
        """
        select id::text as event_id, sequence, event_type, round, payload::text as payload
        from graph_events
        where session_slug = :slug
        order by sequence
        """,
        Map.of("slug", sessionSlug),
        (rs, row) ->
            new StoredEvent(
                rs.getString("event_id"),
                rs.getLong("sequence"),
                rs.getString("event_type"),
                rs.getInt("round"),
                rs.getString("payload")));
  }

  public List<DependencyRow> dependenciesOf(UUID sessionId) {
    return jdbc.query(
        """
        select id::text                     as id,
               round                        as round,
               source_organization_id::text as source_id,
               target_organization_id::text as target_id,
               contributor_hash             as contributor_hash,
               is_seed                      as is_seed,
               status                       as status,
               created_at                   as created_at
        from dependencies
        where session_id = cast(:sessionId as uuid)
        order by created_at, id
        """,
        Map.of("sessionId", sessionId.toString()),
        (rs, row) ->
            new DependencyRow(
                UUID.fromString(rs.getString("id")),
                (Integer) rs.getObject("round"),
                UUID.fromString(rs.getString("source_id")),
                UUID.fromString(rs.getString("target_id")),
                rs.getString("contributor_hash"),
                rs.getBoolean("is_seed"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
  }

  public List<OrganizationRow> organizationsOf(UUID sessionId) {
    return jdbc.query(
        """
        select id::text          as id,
               name              as name,
               normalized_name   as normalized_name,
               organization_type as organization_type,
               jurisdiction      as jurisdiction,
               is_seed           as is_seed,
               created_at        as created_at
        from organizations
        where session_id = cast(:sessionId as uuid)
        order by created_at, id
        """,
        Map.of("sessionId", sessionId.toString()),
        (rs, row) ->
            new OrganizationRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("normalized_name"),
                rs.getString("organization_type"),
                rs.getString("jurisdiction"),
                rs.getBoolean("is_seed"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
  }

  public Optional<DependencyRow> findDependency(UUID dependencyId) {
    return jdbc
        .query(
            """
            select id::text                     as id,
                   round                        as round,
                   source_organization_id::text as source_id,
                   target_organization_id::text as target_id,
                   contributor_hash             as contributor_hash,
                   is_seed                      as is_seed,
                   status                       as status,
                   created_at                   as created_at
            from dependencies
            where id = cast(:id as uuid)
            """,
            Map.of("id", dependencyId.toString()),
            (rs, row) ->
                new DependencyRow(
                    UUID.fromString(rs.getString("id")),
                    (Integer) rs.getObject("round"),
                    UUID.fromString(rs.getString("source_id")),
                    UUID.fromString(rs.getString("target_id")),
                    rs.getString("contributor_hash"),
                    rs.getBoolean("is_seed"),
                    rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .stream()
        .findFirst();
  }

  private static String text(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /**
   * The identifiers of a purpose-built session.
   *
   * <p>{@code supplier}, {@code subSupplier} and {@code carrier} are null for {@link
   * #bareSession()}, which has no organizations beyond its root.
   */
  public record SessionFixture(
      UUID sessionId,
      String slug,
      UUID root,
      UUID supplier,
      UUID subSupplier,
      UUID carrier,
      List<UUID> seedDependencyIds) {

    public SessionFixture {
      seedDependencyIds = List.copyOf(seedDependencyIds);
    }

    /** {@code root -> supplier}. */
    public UUID rootToSupplier() {
      return seedDependencyIds.get(0);
    }

    /** {@code root -> carrier}. */
    public UUID rootToCarrier() {
      return seedDependencyIds.get(1);
    }

    /** {@code supplier -> subSupplier}. */
    public UUID supplierToSubSupplier() {
      return seedDependencyIds.get(2);
    }
  }

  public record StoredEvent(
      String eventId, long sequence, String eventType, int round, String payload) {}

  public record DependencyRow(
      UUID id,
      Integer round,
      UUID sourceOrganizationId,
      UUID targetOrganizationId,
      String contributorHash,
      boolean seed,
      String status,
      Instant createdAt) {}

  public record OrganizationRow(
      UUID id,
      String name,
      String normalizedName,
      String organizationType,
      String jurisdiction,
      boolean seed,
      Instant createdAt) {}
}
