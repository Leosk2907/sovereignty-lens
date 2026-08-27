package eu.sovereigntylens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.support.AbstractDatabaseTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The migrations, applied to a genuinely empty database.
 *
 * <p>The suite's shared database has been written to by every other test by the time this class
 * runs, so a fresh database is created inside the same container and migrated from scratch. That is
 * what makes "a clean clone can start" a claim this test can actually check, and it also lets the
 * seed assertions below be exact counts rather than "at least".
 *
 * <p>The demo session's identifiers are fixed literals in {@code V3__seed_demo_session.sql} on
 * purpose, so fixtures and rehearsal scripts can reference them without querying first. Pinning
 * them here is the point: a migration that renumbered them would break every one of those.
 */
@DisplayName("Flyway migrations")
class FlywayMigrationIT extends AbstractDatabaseTest {

  private static final String PROBE_DATABASE = "sovereignty_lens_migration_probe";

  private static final String DEMO_SESSION_ID = "00000000-0000-4000-8000-000000000001";
  private static final String ROOT_ORGANIZATION_ID = "00000000-0000-4000-8000-000000000101";
  private static final String ALPINE_ID = "00000000-0000-4000-8000-000000000102";
  private static final String BALTIC_ID = "00000000-0000-4000-8000-000000000103";
  private static final String RHINE_ID = "00000000-0000-4000-8000-000000000104";
  private static final String SEED_EDGE_ROOT_TO_ALPINE = "00000000-0000-4000-8000-000000000201";
  private static final String SEED_EDGE_ROOT_TO_RHINE = "00000000-0000-4000-8000-000000000202";
  private static final String SEED_EDGE_ALPINE_TO_BALTIC = "00000000-0000-4000-8000-000000000203";

  private static JdbcTemplate probe;

  @BeforeAll
  static void migrateAnEmptyDatabase() throws Exception {
    createEmptyDatabase();
    Flyway.configure()
        .dataSource(probeUrl(), username(), password())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    probe =
        new JdbcTemplate(
            new DriverManagerDataSource(
                probeUrl(), username(), password()));
  }

  @Test
  void appliesEveryMigrationSuccessfully() {
    List<Map<String, Object>> applied =
        probe.queryForList(
            "select version, description, success from flyway_schema_history"
                + " where version is not null order by installed_rank");

    assertThat(applied).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4");
    assertThat(applied).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));
  }

  @Test
  void seedsExactlyTheFourFictionalOrganizations() {
    List<Map<String, Object>> organizations =
        probe.queryForList(
            "select id::text as id, name, organization_type, jurisdiction, is_seed"
                + " from organizations where session_id = ?::uuid order by id",
            DEMO_SESSION_ID);

    assertThat(organizations)
        .hasSize(4)
        .extracting(row -> row.get("id"))
        .containsExactly(ROOT_ORGANIZATION_ID, ALPINE_ID, BALTIC_ID, RHINE_ID);
    assertThat(organizations)
        .extracting(row -> row.get("name"))
        .containsExactly(
            "European Digital Services Agency",
            "Alpine Civic Systems",
            "Baltic Data Works",
            "Rhine Public Networks");
    assertThat(organizations)
        .allSatisfy(row -> assertThat(row.get("is_seed")).isEqualTo(Boolean.TRUE));

    // The root is the only public body, and it is the session's declared root.
    assertThat(organizations)
        .filteredOn(row -> "government".equals(row.get("organization_type")))
        .extracting(row -> row.get("id"))
        .containsExactly(ROOT_ORGANIZATION_ID);
    assertThat(
            probe.queryForObject(
                "select root_organization_id::text from sessions where id = ?::uuid",
                String.class,
                DEMO_SESSION_ID))
        .isEqualTo(ROOT_ORGANIZATION_ID);
  }

  @Test
  void seedsExactlyThreeDependenciesAllOfThemSeedRows() {
    List<Map<String, Object>> dependencies =
        probe.queryForList(
            "select id::text as id, round, contributor_hash, is_seed, status"
                + " from dependencies where session_id = ?::uuid order by id",
            DEMO_SESSION_ID);

    assertThat(dependencies)
        .hasSize(3)
        .extracting(row -> row.get("id"))
        .containsExactly(
            SEED_EDGE_ROOT_TO_ALPINE, SEED_EDGE_ROOT_TO_RHINE, SEED_EDGE_ALPINE_TO_BALTIC);
    assertThat(dependencies)
        .allSatisfy(
            row -> {
              assertThat(row.get("is_seed")).isEqualTo(Boolean.TRUE);
              assertThat(row.get("status")).isEqualTo("active");
              // A null round is what keeps a seed edge visible in every round.
              assertThat(row.get("round")).isNull();
              assertThat(row.get("contributor_hash")).isNull();
            });

    assertThat(
            probe.queryForList(
                "select source_organization_id::text as source, target_organization_id::text"
                    + " as target from dependencies where session_id = ?::uuid order by id",
                DEMO_SESSION_ID))
        .extracting(row -> row.get("source") + " -> " + row.get("target"))
        .containsExactly(
            ROOT_ORGANIZATION_ID + " -> " + ALPINE_ID,
            ROOT_ORGANIZATION_ID + " -> " + RHINE_ID,
            ALPINE_ID + " -> " + BALTIC_ID);
  }

  /**
   * The reveal of an external dependency has to come from the audience. A seed row in any
   * jurisdiction but Europe would let the presenter plant the punchline, which is exactly the
   * accusation the demo must not be able to make.
   */
  @Test
  void plantsNoExternalJurisdictionAnywhereInTheSeed() {
    assertThat(
            probe.queryForList(
                "select distinct jurisdiction from organizations where is_seed", String.class))
        .containsExactly("europe");

    Integer nonEuropeanEndpoints =
        probe.queryForObject(
            """
            select count(*)
            from dependencies d
                     join organizations src on src.id = d.source_organization_id
                     join organizations tgt on tgt.id = d.target_organization_id
            where d.is_seed
              and (src.jurisdiction <> 'europe' or tgt.jurisdiction <> 'europe')
            """,
            Integer.class);
    assertThat(nonEuropeanEndpoints).isZero();
  }

  @Test
  void seedsTheDemoSessionOpenOnRoundOneWithNoAudienceRows() {
    Map<String, Object> session =
        probe.queryForMap(
            "select slug, title, status, current_round from sessions where id = ?::uuid",
            DEMO_SESSION_ID);

    assertThat(session.get("slug")).isEqualTo("demo");
    assertThat(session.get("title")).isEqualTo("Sovereignty Lens live demo");
    assertThat(session.get("status")).isEqualTo("open");
    assertThat(session.get("current_round")).isEqualTo(1);

    assertThat(probe.queryForObject("select count(*) from sessions", Integer.class)).isEqualTo(1);
    assertThat(probe.queryForObject("select count(*) from dependencies where not is_seed",
            Integer.class))
        .isZero();
    assertThat(probe.queryForObject("select count(*) from graph_events", Integer.class)).isZero();
  }

  private static void createEmptyDatabase() throws Exception {
    // CREATE DATABASE cannot run inside a transaction, so this uses a plain autocommit connection
    // against the container's default database rather than the application's DataSource.
    try (Connection admin =
            DriverManager.getConnection(
                jdbcUrl(),
                username(),
                password());
        Statement statement = admin.createStatement()) {
      statement.execute("drop database if exists " + PROBE_DATABASE);
      statement.execute("create database " + PROBE_DATABASE);
    }
  }

  private static String probeUrl() {
    return databaseUrl(PROBE_DATABASE);
  }

  /**
   * Swaps the database name in whichever JDBC URL the suite is running against, so this works
   * unchanged whether that is a Testcontainers instance or an externally supplied server.
   */
  private static String databaseUrl(String databaseName) {
    String url = jdbcUrl();
    int lastSlash = url.lastIndexOf('/');
    int query = url.indexOf('?', lastSlash);
    String suffix = query < 0 ? "" : url.substring(query);
    return url.substring(0, lastSlash + 1) + databaseName + suffix;
  }
}
