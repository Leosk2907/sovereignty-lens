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

  static {
    // Started here and never stopped: Ryuk removes it when the JVM exits.
    POSTGRES.start();
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
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
