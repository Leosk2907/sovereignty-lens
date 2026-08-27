package eu.sovereigntylens.application;

import eu.sovereigntylens.config.AppProperties;
import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.ContributionCommand;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.SubmissionResult;
import eu.sovereigntylens.domain.port.ContributionRepository;
import eu.sovereigntylens.domain.service.Normalizer;
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
            hasher.hash(command.anonymousClientId()),
            properties.roundCapacity()));
  }

  /**
   * Parsed up front so that a malformed identifier is a validation failure naming its field, rather
   * than a cast error the database would raise as an unattributable internal fault.
   */
  private static UUID sourceOrganizationId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw DomainException.validation(
          "The selected organization is not a valid identifier.", "sourceOrganizationId");
    }
  }
}
