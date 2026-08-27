package eu.sovereigntylens.domain.port;

import eu.sovereigntylens.domain.DomainException;
import eu.sovereigntylens.domain.model.CompanyProfileResult;
import eu.sovereigntylens.domain.model.CompanyProfileSubmission;
import eu.sovereigntylens.domain.model.DependencySubmission;
import eu.sovereigntylens.domain.model.SubmissionResult;

/**
 * Stores one audience contribution.
 *
 * <p>The whole submission is a single port method because it cannot be decomposed without losing
 * atomicity: the session lock, the quota check, the duplicate checks, the insert and the live event
 * must all succeed or all be discarded together. An implementation that read state, returned, and
 * wrote later would let two phones pass the same capacity check.
 */
public interface ContributionRepository {

  /**
   * Persists a contribution and emits its {@code dependency.created} event in one transaction.
   *
   * @throws DomainException when a business invariant the store enforces is violated, carrying
   *     {@code SESSION_NOT_FOUND}, {@code SOURCE_NOT_FOUND}, {@code SESSION_PAUSED}, {@code
   *     ALREADY_CONTRIBUTED}, {@code DUPLICATE_DEPENDENCY}, {@code ROUND_CAPACITY_REACHED} or
   *     {@code VALIDATION_ERROR}
   */
  SubmissionResult submit(DependencySubmission submission);

  /** Persists a complete company profile and emits one event for every created edge. */
  CompanyProfileResult submitCompanyProfile(CompanyProfileSubmission submission);
}
