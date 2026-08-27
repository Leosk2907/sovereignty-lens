package eu.sovereigntylens.domain.model;

import java.util.List;
import java.util.UUID;

/** Validated and normalized company-profile batch ready for one atomic store call. */
public record CompanyProfileSubmission(
    String sessionSlug,
    OrganizationInput company,
    List<UUID> customerOrganizationIds,
    List<OrganizationInput> dependencies,
    String contributorHash,
    int roundCapacity) {

  public CompanyProfileSubmission {
    customerOrganizationIds = List.copyOf(customerOrganizationIds);
    dependencies = List.copyOf(dependencies);
  }

  public record OrganizationInput(
      String displayName,
      String comparisonKey,
      OrganizationType organizationType,
      Jurisdiction jurisdiction) {}
}
