package eu.sovereigntylens.application;

import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.CompanyContributionCommand;
import eu.sovereigntylens.domain.model.CompanyProfileResult;
import eu.sovereigntylens.domain.model.CompanyProfileSubmission;
import eu.sovereigntylens.domain.model.ContributionCommand;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.domain.port.ContributionRepository;
import eu.sovereigntylens.domain.service.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audience contribution use case: one browser adds one dependency to one session.
 *
 * <p>Everything decided here is a business rule rather than a wire concern, which is why the
 * contract version is checked in the web adapter and not in this class.
 */
@Service
public class ContributionService {

  private final ContributionRepository contributions;
  private final ContributorHasher hasher;
  private final AppProperties properties;

  public ContributionService(
      ContributionRepository contributions, ContributorHasher hasher, AppProperties properties) {
    this.contributions = contributions;
    this.hasher = hasher;
    this.properties = properties;
  }

  /**
   * Records a contribution and its live event atomically.
   *
   * <p>The transaction spans the whole call so that the dependency row and the {@code
   * dependency.created} event row commit together: the SSE bridge is woken by {@code pg_notify},
   * which only fires on commit, so a rolled-back contribution can never be broadcast.
   *
   * @throws DomainException when an input is invalid or a session invariant is violated
   */
  @Transactional
  public SubmissionResult contribute(ContributionCommand command) {
    // Rejected here rather than in the database so the audience gets a field-accurate message: the
    // graph's government nodes are seeded by the presenter and the audience may only add suppliers
    // beneath them.
    if (command.targetOrganizationType() == OrganizationType.GOVERNMENT) {
      throw DomainException.validation(
          "A public body cannot be added as a dependency.", "target.organizationType");
    }

    String displayName = Normalizer.displayName(command.targetName());

    return contributions.submit(
        new DependencySubmission(
            command.sessionSlug(),
            sourceOrganizationId(command.sourceOrganizationId()),
            displayName,
            Normalizer.comparisonKey(displayName),
            command.targetOrganizationType(),
            command.targetJurisdiction(),
            hasher.hash(canonicalClientId(command.anonymousClientId())),
            properties.roundCapacity()));
  }

  /** Records one complete company profile and all of its connections atomically. */
  @Transactional
  public CompanyProfileResult contributeCompanyProfile(CompanyContributionCommand command) {
    if (command.company() == null) {
      throw DomainException.validation("Company details are required.", "company");
    }
    if (command.company().organizationType() == OrganizationType.GOVERNMENT) {
      throw DomainException.validation(
          "The contributed company cannot be a public body.", "company.organizationType");
    }
    if (command.company().jurisdiction() != Jurisdiction.EUROPE) {
      throw DomainException.validation(
          "The contributed company must be European.", "company.jurisdiction");
    }
    requireBatchSize(command.customerOrganizationIds(), "customerOrganizationIds");
    requireBatchSize(command.dependencies(), "dependencies");

    List<UUID> customerIds = new ArrayList<>();
    Set<UUID> uniqueCustomerIds = new HashSet<>();
    for (String rawId : command.customerOrganizationIds()) {
      UUID id = organizationId(rawId, "customerOrganizationIds");
      if (!uniqueCustomerIds.add(id)) {
        throw DomainException.validation(
            "Customer organizations must be distinct.", "customerOrganizationIds");
      }
      customerIds.add(id);
    }

    String companyName = Normalizer.displayName(command.company().name(), "company.name");
    String companyKey = Normalizer.comparisonKey(companyName);
    Set<String> profileNames = new HashSet<>();
    profileNames.add(companyKey);
    List<CompanyProfileSubmission.OrganizationInput> providers = new ArrayList<>();
    for (int index = 0; index < command.dependencies().size(); index += 1) {
      CompanyContributionCommand.Provider provider = command.dependencies().get(index);
      String field = "dependencies[" + index + "]";
      if (provider.organizationType() == OrganizationType.GOVERNMENT) {
        throw DomainException.validation(
            "A dependency provider cannot be a public body.", field + ".organizationType");
      }
      String displayName = Normalizer.displayName(provider.name(), field + ".name");
      String comparisonKey = Normalizer.comparisonKey(displayName);
      if (!profileNames.add(comparisonKey)) {
        throw DomainException.validation(
            "Company and dependency names must be distinct.", field + ".name");
      }
      providers.add(
          new CompanyProfileSubmission.OrganizationInput(
              displayName,
              comparisonKey,
              provider.organizationType(),
              provider.jurisdiction()));
    }

    CompanyProfileSubmission.OrganizationInput company =
        new CompanyProfileSubmission.OrganizationInput(
            companyName,
            companyKey,
            command.company().organizationType(),
            command.company().jurisdiction());
    return contributions.submitCompanyProfile(
        new CompanyProfileSubmission(
            command.sessionSlug(),
            company,
            customerIds,
            providers,
            hasher.hash(canonicalClientId(command.anonymousClientId())),
            properties.roundCapacity()));
  }

  private static void requireBatchSize(List<?> values, String field) {
    if (values == null || values.isEmpty() || values.size() > 3) {
      throw DomainException.validation("Choose between one and three entries.", field);
    }
  }

  /**
   * Parsed up front so that a malformed identifier is a validation failure naming its field, rather
   * than a cast error the database would raise as an unattributable internal fault.
   */
  private static UUID sourceOrganizationId(String raw) {
    return organizationId(raw, "sourceOrganizationId");
  }

  private static UUID organizationId(String raw, String field) {
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equalsIgnoreCase(raw)) {
        throw new IllegalArgumentException("UUID is not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw DomainException.validation(
          "The selected organization is not a valid identifier.", field);
    }
  }

  private static String canonicalClientId(String raw) {
    return organizationId(raw, "anonymousClientId").toString();
  }
}
