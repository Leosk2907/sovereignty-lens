package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Session lifecycle state. */
public enum SessionStatus {
  OPEN("open"),
  PAUSED("paused");

  private final String wireValue;

  SessionStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static SessionStatus fromWire(String value) {
    for (SessionStatus candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown session status: " + value);
  }
}
