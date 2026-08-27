package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.sovereigntylens.application.ContributionService;
import eu.sovereigntylens.application.ContributorHasher;
import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.ContributionCommand;
import eu.sovereigntylens.domain.model.CompanyProfileResult;
import eu.sovereigntylens.domain.model.CompanyProfileSubmission;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.DomainErrorCode;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.domain.port.ContributionRepository;
import eu.sovereigntylens.fixture.Fixtures;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The contribution use case against a recording fake port. A fake rather than a mock because every
 * test here wants to inspect the {@link DependencySubmission} that reached the boundary, and a
 * captor plus stubbing would be more machinery than the two-line fake below.
 */
@DisplayName("ContributionService")
class ContributionServiceTest {

  private final RecordingContributionRepository repository = new RecordingContributionRepository();
  private final ContributorHasher hasher = new ContributorHasher(AppPropertiesFixture.defaults());

  private ContributionService service(AppProperties properties) {
    return new ContributionService(repository, new ContributorHasher(properties), properties);
  }

  private ContributionService service() {
    return service(AppPropertiesFixture.defaults());
  }

  @Test
  void rejectsAGovernmentTargetBeforeReachingTheStore() {
    ContributionCommand command =
        command("Ministry of Everything", OrganizationType.GOVERNMENT, Jurisdiction.EUROPE);

    assertThatThrownBy(() -> service().contribute(command))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
              assertThat(exception.field()).isEqualTo("target.organizationType");
            });
    assertThat(repository.received).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = OrganizationType.class,
      names = "GOVERNMENT",
      mode = EnumSource.Mode.EXCLUDE)
  void acceptsEveryNonGovernmentTargetType(OrganizationType type) {
    service().contribute(command("Northwind Cloud", type, Jurisdiction.UNITED_STATES));

    assertThat(repository.received.targetOrganizationType()).isEqualTo(type);
  }

  @Test
  void normalizesTheDisplayNameBeforeHandingItToThePort() {
    service()
        .contribute(
            command("   Northwind    Cloud  ", OrganizationType.CLOUD, Jurisdiction.UNITED_STATES));

    assertThat(repository.received.targetDisplayName()).isEqualTo("Northwind Cloud");
  }

  @Test
  void derivesTheFoldedComparisonKeyFromTheNormalizedName() {
    service()
        .contribute(
            command("  NORTHWIND   Cloud ", OrganizationType.CLOUD, Jurisdiction.UNITED_STATES));

    assertThat(repository.received.targetComparisonKey()).isEqualTo("northwind cloud");
  }

  @Test
  void rejectsATargetNameTooShortToBeACompany() {
    ContributionCommand command = command("a", OrganizationType.CLOUD, Jurisdiction.UNKNOWN);

    assertThatThrownBy(() -> service().contribute(command))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.field()).isEqualTo("target.name"));
    assertThat(repository.received).isNull();
  }

  @Test
  void reportsAMalformedSourceIdentifierAgainstItsOwnFieldRatherThanAsAnInternalFault() {
    ContributionCommand command =
        new ContributionCommand(
            Fixtures.SESSION_SLUG,
            Fixtures.ANONYMOUS_CLIENT_ID,
            "not-a-uuid",
            "Northwind Cloud",
            OrganizationType.CLOUD,
            Jurisdiction.UNITED_STATES);

    assertThatThrownBy(() -> service().contribute(command))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(DomainErrorCode.VALIDATION_ERROR);
              assertThat(exception.field()).isEqualTo("sourceOrganizationId");
            });
    assertThat(repository.received).isNull();
  }

  @Test
  void passesTheSourceIdentifierThroughAsAParsedUuid() {
    service().contribute(command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.CHINA));

    assertThat(repository.received.sourceOrganizationId())
        .isEqualTo(UUID.fromString(Fixtures.BALTIC_ID));
  }

  @Test
  void passesTheConfiguredRoundCapacityThroughToTheStore() {
    service(AppPropertiesFixture.withRoundCapacity(7))
        .contribute(command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.CHINA));

    assertThat(repository.received.roundCapacity()).isEqualTo(7);
  }

  @Test
  void reachesTheStoreWithTheClientIdentifierOnlyInHashedForm() {
    service().contribute(command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.EUROPE));

    DependencySubmission submission = repository.received;
    assertThat(submission.contributorHash())
        .isNotEqualTo(Fixtures.ANONYMOUS_CLIENT_ID)
        .doesNotContain(Fixtures.ANONYMOUS_CLIENT_ID)
        .isEqualTo(hasher.hash(Fixtures.ANONYMOUS_CLIENT_ID));
  }

  @Test
  void carriesTheSessionSlugAndJurisdictionThroughUnchanged() {
    service().contribute(command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.CHINA));

    assertThat(repository.received.sessionSlug()).isEqualTo(Fixtures.SESSION_SLUG);
    assertThat(repository.received.targetJurisdiction()).isEqualTo(Jurisdiction.CHINA);
  }

  @Test
  void returnsWhateverThePortCommitted() {
    repository.result = Fixtures.submissionResult();

    SubmissionResult result =
        service().contribute(command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.CHINA));

    assertThat(result).isEqualTo(Fixtures.submissionResult());
  }

  @Test
  void propagatesAPortFailureUnchangedSoTheHandlerSeesTheOriginalCode() {
    repository.failure =
        new DomainException(DomainErrorCode.ROUND_CAPACITY_REACHED, "This round is full.");

    assertThatThrownBy(
            () ->
                service()
                    .contribute(
                        command("Northwind Cloud", OrganizationType.CLOUD, Jurisdiction.CHINA)))
        .isSameAs(repository.failure);
  }

  private static ContributionCommand command(
      String targetName, OrganizationType type, Jurisdiction jurisdiction) {
    return new ContributionCommand(
        Fixtures.SESSION_SLUG,
        Fixtures.ANONYMOUS_CLIENT_ID,
        Fixtures.BALTIC_ID,
        targetName,
        type,
        jurisdiction);
  }

  /** Records the one submission it is handed, and optionally fails the way the store would. */
  private static final class RecordingContributionRepository implements ContributionRepository {

    private DependencySubmission received;
    private DomainException failure;
    private SubmissionResult result = Fixtures.submissionResult();

    @Override
    public SubmissionResult submit(DependencySubmission submission) {
      received = submission;
      if (failure != null) {
        throw failure;
      }
      return result;
    }

    @Override
    public CompanyProfileResult submitCompanyProfile(CompanyProfileSubmission submission) {
      throw new UnsupportedOperationException("Not used by the legacy contribution tests");
    }
  }
}
