package eu.sovereigntylens.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Atomic audience submission for one European company profile. */
public record CompanyContributionRequest(
    @NotNull Integer contractVersion,
    @NotBlank String anonymousClientId,
    @NotNull @Valid Company company,
    @NotEmpty @Size(max = 3) List<@NotBlank String> customerOrganizationIds,
    @NotEmpty @Size(max = 3) List<@NotNull @Valid Dependency> dependencies) {

  public record Company(
      @NotBlank String name,
      @NotNull OrganizationType organizationType,
      @NotNull Jurisdiction jurisdiction) {}

  public record Dependency(
      @NotBlank String name,
      @NotNull OrganizationType organizationType,
      @NotNull Jurisdiction jurisdiction) {}
}
