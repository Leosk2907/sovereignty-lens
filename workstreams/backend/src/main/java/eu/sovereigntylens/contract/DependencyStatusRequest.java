package eu.sovereigntylens.contract;

import jakarta.validation.constraints.NotNull;

/** Hide or restore one current-round audience dependency. */
public record DependencyStatusRequest(
    @NotNull Integer contractVersion, @NotNull DependencyStatus status) {}
