package eu.sovereigntylens.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Shared-password presenter login. */
public record AdminLoginRequest(@NotNull Integer contractVersion, @NotBlank String password) {}
