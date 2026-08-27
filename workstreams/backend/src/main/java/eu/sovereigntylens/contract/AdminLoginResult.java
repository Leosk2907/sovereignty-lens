package eu.sovereigntylens.contract;

/** Successful presenter login. The session itself lives in a signed HTTP-only cookie. */
public record AdminLoginResult(int contractVersion, boolean authenticated) {

  public static AdminLoginResult ok() {
    return new AdminLoginResult(ContractVersion.CURRENT, true);
  }
}
