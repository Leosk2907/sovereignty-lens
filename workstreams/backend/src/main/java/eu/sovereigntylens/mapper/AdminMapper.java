package eu.sovereigntylens.mapper;

import eu.sovereigntylens.contract.AdminActionResult;
import eu.sovereigntylens.contract.AdminDependency;
import eu.sovereigntylens.contract.AdminDependencyList;
import eu.sovereigntylens.domain.model.AdminDependencyView;
import eu.sovereigntylens.domain.model.AdminOutcome;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.Session;
import java.util.List;

/**
 * Domain to contract translation for the presenter API.
 *
 * <p>Nodes and edges are handed to {@link GraphMapper}. The presenter view of an edge has to be
 * byte-identical to the public one - the admin screen and the presentation are looking at the same
 * graph - so there is exactly one place that decides what an edge looks like on the wire.
 */
public final class AdminMapper {

  private AdminMapper() {}

  public static AdminActionResult toContract(AdminOutcome outcome) {
    return AdminActionResult.of(outcome.eventId(), GraphMapper.toContract(outcome.session()));
  }

  public static AdminDependency toContract(AdminDependencyView view) {
    return new AdminDependency(
        GraphMapper.toContract(view.edge()),
        GraphMapper.toContract(view.source()),
        GraphMapper.toContract(view.target()));
  }

  /** The review-list envelope, carrying the session state the rows were read with. */
  public static AdminDependencyList toContract(Session session, List<AdminDependencyView> views) {
    return AdminDependencyList.of(
        GraphMapper.toContract(session), views.stream().map(AdminMapper::toContract).toList());
  }

  /**
   * The one inbound translation the presenter API needs.
   *
   * <p>Matched on the shared wire token rather than on the enum constant, so a contract value with
   * no domain twin fails here instead of being silently mapped to the wrong state.
   */
  public static DependencyStatus toDomain(eu.sovereigntylens.contract.DependencyStatus status) {
    return DependencyStatus.fromWire(status.wireValue());
  }
}
