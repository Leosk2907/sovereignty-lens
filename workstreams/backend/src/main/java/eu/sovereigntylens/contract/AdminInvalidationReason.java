package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Reason carried by a {@code graph.invalidated} event. */
public enum AdminInvalidationReason {
  PAUSE("pause"),
  RESUME("resume"),
  HIDE("hide"),
  RESTORE("restore"),
  UNDO("undo"),
  RESET("reset");

  private final String wireValue;

  AdminInvalidationReason(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static AdminInvalidationReason fromWire(String value) {
    for (AdminInvalidationReason candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown invalidation reason: " + value);
  }
}
