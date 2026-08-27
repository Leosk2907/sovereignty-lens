package eu.sovereigntylens.domain.model;

/**
 * Where an organization is legally reachable from.
 *
 * <p>The domain twin of the contract enum. It exists separately so that the business rule "which
 * jurisdictions count as external exposure" can evolve without touching a published wire format.
 *
 * <p>{@code unknown} is deliberately not external: an unresolved jurisdiction is a gap in the
 * audience's knowledge, and counting it as exposure would overstate the finding on stage.
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

  /** The snake_case token stored in the database and used on the wire. */
  public String wireValue() {
    return wireValue;
  }

  /** True when reaching this jurisdiction constitutes an external dependency exposure. */
  public boolean isExternal() {
    return external;
  }

  public static Jurisdiction fromWire(String value) {
    for (Jurisdiction candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown jurisdiction: " + value);
  }
}
