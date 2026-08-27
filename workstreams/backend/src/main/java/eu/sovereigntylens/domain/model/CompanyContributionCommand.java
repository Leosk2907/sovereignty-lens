package eu.sovereigntylens.domain.model;

import java.util.List;

/** Raw company-profile request expressed without web-framework types. */
public record CompanyContributionCommand(
    String sessionSlug,
    String anonymousClientId,
    Company company,
    List<String> customerOrganizationIds,
    List<Provider> dependencies) {

  public record Company(
      String name, OrganizationType organizationType, Jurisdiction jurisdiction) {}

  public record Provider(
      String name, OrganizationType organizationType, Jurisdiction jurisdiction) {}
}
