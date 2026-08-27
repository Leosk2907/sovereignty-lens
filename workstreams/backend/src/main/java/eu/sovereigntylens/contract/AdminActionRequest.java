package eu.sovereigntylens.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Wraps a discriminated admin action, matching {@code { "action": { "type": "pause" } }}. */
public record AdminActionRequest(@NotNull Integer contractVersion, @NotNull @Valid Action action) {

  public record Action(@NotNull AdminActionType type) {}
}
