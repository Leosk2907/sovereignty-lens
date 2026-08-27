package eu.sovereigntylens.mapper;

import eu.sovereigntylens.contract.GraphEdge;
import eu.sovereigntylens.contract.GraphNode;
import eu.sovereigntylens.contract.GraphSnapshot;
import eu.sovereigntylens.contract.SessionSummary;
import eu.sovereigntylens.domain.model.Dependency;
import eu.sovereigntylens.domain.model.DependencyStatus;
import eu.sovereigntylens.domain.model.GraphView;
import eu.sovereigntylens.domain.model.Jurisdiction;
import eu.sovereigntylens.domain.model.Organization;
import eu.sovereigntylens.domain.model.OrganizationType;
import eu.sovereigntylens.domain.model.Session;
import eu.sovereigntylens.domain.model.SessionStatus;
import java.time.Instant;
import java.util.List;

/**
 * The only place a domain graph model becomes a version-1 contract DTO.
 *
 * <p>Enums are converted by {@code name()} rather than by wire value on purpose. The two enum sets
 * are twins that must stay in step, and a name lookup throws the moment one side gains a constant
 * the other lacks; matching on wire strings would silently accept a drifted spelling. A contract
 * test asserts the two sets are identical, so such a divergence fails the build rather than the
 * demo.
 */
public final class GraphMapper {

  private GraphMapper() {}

  /** Assembles the authoritative public read, stamping the server clock the client compares to. */
  public static GraphSnapshot toContract(GraphView view, Instant serverTime) {
    return GraphSnapshot.of(
        toContract(view.session()),
        view.nodes().stream().map(GraphMapper::toContract).toList(),
        view.edges().stream().map(GraphMapper::toContract).toList(),
        serverTime);
  }

  public static SessionSummary toContract(Session session) {
    return new SessionSummary(
        session.id(),
        session.slug(),
        session.title(),
        toContract(session.status()),
        session.currentRound(),
        session.rootOrganizationId());
  }

  public static GraphNode toContract(Organization organization) {
    return new GraphNode(
        organization.id(),
        organization.name(),
        toContract(organization.organizationType()),
        toContract(organization.jurisdiction()),
        organization.seed());
  }

  public static GraphEdge toContract(Dependency dependency) {
    return new GraphEdge(
        dependency.id(),
        dependency.sourceOrganizationId(),
        dependency.targetOrganizationId(),
        dependency.seed(),
        toContract(dependency.status()),
        dependency.createdAt());
  }

  public static eu.sovereigntylens.contract.OrganizationType toContract(OrganizationType value) {
    return eu.sovereigntylens.contract.OrganizationType.valueOf(value.name());
  }

  public static eu.sovereigntylens.contract.Jurisdiction toContract(Jurisdiction value) {
    return eu.sovereigntylens.contract.Jurisdiction.valueOf(value.name());
  }

  public static eu.sovereigntylens.contract.SessionStatus toContract(SessionStatus value) {
    return eu.sovereigntylens.contract.SessionStatus.valueOf(value.name());
  }

  public static eu.sovereigntylens.contract.DependencyStatus toContract(DependencyStatus value) {
    return eu.sovereigntylens.contract.DependencyStatus.valueOf(value.name());
  }

  /** Inbound direction, for request DTOs that carry a value enum into a use case. */
  public static OrganizationType toDomain(eu.sovereigntylens.contract.OrganizationType value) {
    return OrganizationType.valueOf(value.name());
  }

  public static Jurisdiction toDomain(eu.sovereigntylens.contract.Jurisdiction value) {
    return Jurisdiction.valueOf(value.name());
  }

  public static SessionStatus toDomain(eu.sovereigntylens.contract.SessionStatus value) {
    return SessionStatus.valueOf(value.name());
  }

  public static DependencyStatus toDomain(eu.sovereigntylens.contract.DependencyStatus value) {
    return DependencyStatus.valueOf(value.name());
  }

  /** Convenience for adapters that map a whole page of organizations. */
  public static List<GraphNode> toContractNodes(List<Organization> organizations) {
    return organizations.stream().map(GraphMapper::toContract).toList();
  }

  /** Convenience for adapters that map a whole page of dependencies. */
  public static List<GraphEdge> toContractEdges(List<Dependency> dependencies) {
    return dependencies.stream().map(GraphMapper::toContract).toList();
  }
}
