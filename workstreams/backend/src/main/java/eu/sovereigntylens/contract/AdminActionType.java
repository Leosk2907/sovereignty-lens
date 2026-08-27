package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Presenter recovery actions. */
public enum AdminActionType {
  PAUSE("pause"),
  RESUME("resume"),
  RESET("reset"),
  UNDO("undo");

  private final String wireValue;

  AdminActionType(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static AdminActionType fromWire(String value) {
    for (AdminActionType candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown admin action: " + value);
  }
}
