package eu.sovereigntylens.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need real SQL.
 *
 * <p>The container is a singleton started once per JVM rather than a JUnit {@code @Container} field,
 * because starting PostgreSQL per test class dominates the suite runtime and every one of these
 * tests is happy to share a server. Isolation comes from each test writing into its own session
 * rows, not from a fresh database: {@link DatabaseFixtures} mints a new session slug per fixture, so
 * nothing here truncates or resets between tests and no test may write to the seeded {@code demo}
 * session.
 *
 * <p>Tagged {@code integration}, so Surefire skips these during {@code mvn test} and Failsafe runs
 * them during {@code mvn verify}. They need a working Docker daemon.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractDatabaseTest {

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("sovereignty_lens_test")
          .withUsername("sovereignty_test")
          .withPassword("sovereignty_test");

  /**
   * An already-running PostgreSQL to use instead of starting a container.
   *
   * <p>Testcontainers needs to talk to a Docker daemon itself, which is not always possible - some
   * Docker Desktop builds refuse the engine API to non-CLI clients, and CI images without
   * Docker-in-Docker cannot do it at all. Pointing these three variables at a database that is
   * already up runs the identical suite against identical SQL. The account must be able to create a
   * database, because {@code FlywayMigrationIT} makes its own empty one.
   */
  private static final String EXTERNAL_URL = System.getenv("SOVEREIGNTY_TEST_DB_URL");

  private static final String EXTERNAL_USERNAME = System.getenv("SOVEREIGNTY_TEST_DB_USERNAME");

  private static final String EXTERNAL_PASSWORD = System.getenv("SOVEREIGNTY_TEST_DB_PASSWORD");

  static {
    if (EXTERNAL_URL == null) {
      // Started here and never stopped: Ryuk removes it when the JVM exits.
      POSTGRES.start();
    }
  }

  @Autowired protected NamedParameterJdbcTemplate jdbc;

  @Autowired private PlatformTransactionManager transactionManager;

  /** Row builders and inspectors. Rebuilt per test so no state can leak between methods. */
  protected DatabaseFixtures fixtures;

  /**
   * A programmatic transaction boundary.
   *
   * <p>The atomicity tests need to commit or roll back around a repository call from outside it,
   * which {@code @Transactional} on the test class cannot express: that would roll everything back
   * and leave "did this really persist?" unanswerable.
   */
  protected TransactionTemplate transactions;

  @BeforeEach
  protected void prepareDatabaseSupport() {
    fixtures = new DatabaseFixtures(jdbc);
    transactions = new TransactionTemplate(transactionManager);
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    if (EXTERNAL_URL != null) {
      registry.add("spring.datasource.url", () -> EXTERNAL_URL);
      registry.add("spring.datasource.username", () -> EXTERNAL_USERNAME);
      registry.add("spring.datasource.password", () -> EXTERNAL_PASSWORD);
      return;
    }
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  /** JDBC coordinates of whichever database the suite is running against. */
  public static String jdbcUrl() {
    return EXTERNAL_URL != null ? EXTERNAL_URL : POSTGRES.getJdbcUrl();
  }

  public static String username() {
    return EXTERNAL_URL != null ? EXTERNAL_USERNAME : POSTGRES.getUsername();
  }

  public static String password() {
    return EXTERNAL_URL != null ? EXTERNAL_PASSWORD : POSTGRES.getPassword();
  }
}
