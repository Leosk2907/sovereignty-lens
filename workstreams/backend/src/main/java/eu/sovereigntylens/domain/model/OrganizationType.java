package eu.sovereigntylens.domain.model;

/** Sector of an organization, as chosen by the contributor. */
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

  /** The snake_case token stored in the database and used on the wire. */
  public String wireValue() {
    return wireValue;
  }

  public static OrganizationType fromWire(String value) {
    for (OrganizationType candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown organization type: " + value);
  }
}
