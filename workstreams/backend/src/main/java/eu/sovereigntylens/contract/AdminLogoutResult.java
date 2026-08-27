package eu.sovereigntylens.contract;

/** Successful presenter logout. */
public record AdminLogoutResult(int contractVersion, boolean authenticated) {

  public static AdminLogoutResult loggedOut() {
    return new AdminLogoutResult(ContractVersion.CURRENT, false);
  }
}
