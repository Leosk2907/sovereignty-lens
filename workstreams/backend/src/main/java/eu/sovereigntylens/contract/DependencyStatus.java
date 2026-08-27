package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Visibility state of a dependency edge. */
public enum DependencyStatus {
  ACTIVE("active"),
  HIDDEN("hidden");

  private final String wireValue;

  DependencyStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static DependencyStatus fromWire(String value) {
    for (DependencyStatus candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown dependency status: " + value);
  }
}
