package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical jurisdiction enumeration from contracts/data-contract.md.
 *
 * <p>{@code united_states}, {@code china} and {@code other_external} count as external exposure.
 * {@code europe} and {@code unknown} do not: unknown means unresolved, not European.
 */
public enum Jurisdiction {
  EUROPE("europe", false),
  UNITED_STATES("united_states", true),
  CHINA("china", true),
  OTHER_EXTERNAL("other_external", true),
  UNKNOWN("unknown", false);

  private final String wireValue;
  private final boolean external;

  Jurisdiction(String wireValue, boolean external) {
    this.wireValue = wireValue;
    this.external = external;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** True when reaching this jurisdiction constitutes an external dependency exposure. */
  public boolean isExternal() {
    return external;
  }

  @JsonCreator
  public static Jurisdiction fromWire(String value) {
    for (Jurisdiction candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown jurisdiction: " + value);
  }
}
