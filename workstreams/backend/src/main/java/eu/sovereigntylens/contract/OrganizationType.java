package eu.sovereigntylens.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Canonical organization type enumeration from contracts/data-contract.md. */
public enum OrganizationType {
  GOVERNMENT("government"),
  CLOUD("cloud"),
  SOFTWARE("software"),
  HARDWARE("hardware"),
  TELECOM("telecom"),
  CONSULTING("consulting"),
  LOGISTICS("logistics"),
  FINANCE("finance"),
  OTHER("other");

  private final String wireValue;

  OrganizationType(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static OrganizationType fromWire(String value) {
    for (OrganizationType candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown organization type: " + value);
  }
}
