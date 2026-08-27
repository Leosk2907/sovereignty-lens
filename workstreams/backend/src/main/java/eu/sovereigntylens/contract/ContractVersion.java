package eu.sovereigntylens.contract;

/** Version marker shared by every HTTP body and live event. */
public final class ContractVersion {

  /** Current wire contract version. See contracts/data-contract.md. */
  public static final int CURRENT = 1;

  private ContractVersion() {}
}
