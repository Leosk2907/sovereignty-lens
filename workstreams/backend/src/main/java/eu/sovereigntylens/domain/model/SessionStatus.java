package eu.sovereigntylens.domain.model;

/** Whether a session currently accepts contributions. */
public enum SessionStatus {
  OPEN("open"),
  PAUSED("paused");

  private final String wireValue;

  SessionStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The snake_case token stored in the database and used on the wire. */
  public String wireValue() {
    return wireValue;
  }

  public static SessionStatus fromWire(String value) {
    for (SessionStatus candidate : values()) {
      if (candidate.wireValue.equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Unknown session status: " + value);
  }
}
