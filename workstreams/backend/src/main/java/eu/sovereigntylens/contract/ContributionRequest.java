package eu.sovereigntylens.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Audience submission payload: one organization gains one dependency. */
public record ContributionRequest(
    @NotNull Integer contractVersion,
    @NotBlank String anonymousClientId,
    @NotBlank String sourceOrganizationId,
    @NotNull @Valid Target target) {

  /**
   * The company the selected source organization depends on.
   *
   * <p>{@code organizationType} may not be {@code government}; that invariant is enforced in the
   * service layer so it maps to a contract error code rather than a bean-validation message.
   */
  public record Target(
      /*
       * Length is deliberately NOT enforced with @Size here. The contract counts
       * Unicode characters, while @Size on a String counts UTF-16 units, so a
       * 31-character name written in astral characters measures 62 and would be
       * rejected at the web boundary. Normalizer owns the 2-60 code-point rule
       * and applies it to the normalized form, which is the only form worth
       * measuring anyway.
       */
      @NotBlank String name,
      @NotNull OrganizationType organizationType,
      @NotNull Jurisdiction jurisdiction) {}
}
