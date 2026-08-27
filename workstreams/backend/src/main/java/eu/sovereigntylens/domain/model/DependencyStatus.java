package eu.sovereigntylens.domain.model;

/**
 * Visibility state of a dependency.
 *
 * <p>Hiding is reversible and never deletes: a presenter who removes an off-topic submission on
 * stage must be able to put it back, and the admin list keeps showing it.
 */
public enum DependencyStatus {
  ACTIVE("active"),
  HIDDEN("hidden");

  private final String wireValue;

  DependencyStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The snake_case token stored in the database and used on the wire. */
  public String wireValue() {
    return wireValue;
  }

  public static DependencyStatus fromWire(String value) {
    for (DependencyStatus candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown dependency status: " + value);
  }
}
