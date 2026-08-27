package eu.sovereigntylens.unit;

import static org.assertj.core.api.Assertions.assertThat;

import eu.sovereigntylens.contract.GraphEdge;
import eu.sovereigntylens.contract.GraphNode;
import eu.sovereigntylens.contract.GraphSnapshot;
import eu.sovereigntylens.contract.SessionSummary;
import eu.sovereigntylens.fixture.Fixtures;
import eu.sovereigntylens.mapper.GraphMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("GraphMapper")
class GraphMapperTest {

  @Nested
  @DisplayName("domain to contract records")
  class RecordMapping {

    @Test
    void mapsAnOrganizationToItsNode() {
      GraphNode node = GraphMapper.toContract(Fixtures.domainContributedOrganization());

      assertThat(node).isEqualTo(Fixtures.contributedNode());
    }

    @Test
    void mapsADependencyToItsEdgeKeepingTheDirectionSourceDependsOnTarget() {
      GraphEdge edge = GraphMapper.toContract(Fixtures.domainContributedDependency());

      assertThat(edge).isEqualTo(Fixtures.contributedEdge());
      assertThat(edge.sourceOrganizationId()).isEqualTo(Fixtures.BALTIC_ID);
      assertThat(edge.targetOrganizationId()).isEqualTo(Fixtures.CONTRIBUTED_NODE_ID);
    }

    @Test
    void preservesTheSeedFlagOnBothNodesAndEdges() {
      assertThat(GraphMapper.toContract(Fixtures.domainRootOrganization()).isSeed()).isTrue();
      assertThat(GraphMapper.toContract(Fixtures.domainContributedOrganization()).isSeed())
          .isFalse();
      assertThat(GraphMapper.toContract(Fixtures.domainSeedDependency()).isSeed()).isTrue();
      assertThat(GraphMapper.toContract(Fixtures.domainContributedDependency()).isSeed()).isFalse();
    }

    @Test
    void mapsASessionToItsSummary() {
      SessionSummary summary = GraphMapper.toContract(Fixtures.domainSession());

      assertThat(summary).isEqualTo(Fixtures.sessionSummary());
    }

    @Test
    void stampsTheSuppliedServerTimeOntoTheSnapshotRatherThanReadingAClock() {
      Instant serverTime = Instant.parse("2026-03-01T10:15:31Z");

      GraphSnapshot snapshot = GraphMapper.toContract(Fixtures.domainGraphView(), serverTime);

      assertThat(snapshot.serverTime()).isEqualTo(serverTime);
      assertThat(snapshot.contractVersion()).isEqualTo(1);
      assertThat(snapshot.session()).isEqualTo(Fixtures.sessionSummary());
      assertThat(snapshot.nodes())
          .containsExactly(
              GraphMapper.toContract(Fixtures.domainRootOrganization()),
              GraphMapper.toContract(Fixtures.domainAlpineOrganization()),
              GraphMapper.toContract(Fixtures.domainBalticOrganization()));
      assertThat(snapshot.edges())
          .containsExactly(GraphMapper.toContract(Fixtures.domainSeedDependency()));
    }

    @Test
    void keepsSnapshotEdgeEndpointsPresentAmongTheSnapshotNodes() {
      GraphSnapshot snapshot =
          GraphMapper.toContract(Fixtures.domainGraphView(), Fixtures.SERVER_TIME);

      var nodeIds = snapshot.nodes().stream().map(GraphNode::id).toList();
      assertThat(snapshot.edges())
          .allSatisfy(
              edge -> {
                assertThat(nodeIds).contains(edge.sourceOrganizationId());
                assertThat(nodeIds).contains(edge.targetOrganizationId());
              });
    }

    @Test
    void mapsWholeCollectionsInOrder() {
      assertThat(
              GraphMapper.toContractNodes(
                  java.util.List.of(
                      Fixtures.domainRootOrganization(), Fixtures.domainBalticOrganization())))
          .containsExactly(Fixtures.rootNode(), Fixtures.balticNode());
      assertThat(GraphMapper.toContractEdges(java.util.List.of(Fixtures.domainSeedDependency())))
          .containsExactly(Fixtures.seedEdgeRootToAlpine());
    }
  }

  /**
   * The domain and contract value enums are twins that must stay in step. Comparing whole
   * name-to-wire-value maps checks both directions at once: a constant added on either side, or a
   * wire spelling changed on one, breaks the equality.
   */
  @Nested
  @DisplayName("enum twins")
  class EnumTwins {

    @Test
    void jurisdictionSetsAreIdentical() {
      assertThat(
              wireValues(
                  eu.sovereigntylens.domain.model.Jurisdiction.values(),
                  eu.sovereigntylens.domain.model.Jurisdiction::wireValue))
          .isEqualTo(
              wireValues(
                  eu.sovereigntylens.contract.Jurisdiction.values(),
                  eu.sovereigntylens.contract.Jurisdiction::wireValue));
    }

    @Test
    void organizationTypeSetsAreIdentical() {
      assertThat(
              wireValues(
                  eu.sovereigntylens.domain.model.OrganizationType.values(),
                  eu.sovereigntylens.domain.model.OrganizationType::wireValue))
          .isEqualTo(
              wireValues(
                  eu.sovereigntylens.contract.OrganizationType.values(),
                  eu.sovereigntylens.contract.OrganizationType::wireValue));
    }

    @Test
    void sessionStatusSetsAreIdentical() {
      assertThat(
              wireValues(
                  eu.sovereigntylens.domain.model.SessionStatus.values(),
                  eu.sovereigntylens.domain.model.SessionStatus::wireValue))
          .isEqualTo(
              wireValues(
                  eu.sovereigntylens.contract.SessionStatus.values(),
                  eu.sovereigntylens.contract.SessionStatus::wireValue));
    }

    @Test
    void dependencyStatusSetsAreIdentical() {
      assertThat(
              wireValues(
                  eu.sovereigntylens.domain.model.DependencyStatus.values(),
                  eu.sovereigntylens.domain.model.DependencyStatus::wireValue))
          .isEqualTo(
              wireValues(
                  eu.sovereigntylens.contract.DependencyStatus.values(),
                  eu.sovereigntylens.contract.DependencyStatus::wireValue));
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.domain.model.Jurisdiction.class)
    void everyDomainJurisdictionMapsBothWays(eu.sovereigntylens.domain.model.Jurisdiction value) {
      var mapped = GraphMapper.toContract(value);

      assertThat(mapped.wireValue()).isEqualTo(value.wireValue());
      assertThat(GraphMapper.toDomain(mapped)).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.contract.Jurisdiction.class)
    void everyContractJurisdictionHasADomainTwin(eu.sovereigntylens.contract.Jurisdiction value) {
      assertThat(GraphMapper.toContract(GraphMapper.toDomain(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.domain.model.OrganizationType.class)
    void everyDomainOrganizationTypeMapsBothWays(
        eu.sovereigntylens.domain.model.OrganizationType value) {
      var mapped = GraphMapper.toContract(value);

      assertThat(mapped.wireValue()).isEqualTo(value.wireValue());
      assertThat(GraphMapper.toDomain(mapped)).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.contract.OrganizationType.class)
    void everyContractOrganizationTypeHasADomainTwin(
        eu.sovereigntylens.contract.OrganizationType value) {
      assertThat(GraphMapper.toContract(GraphMapper.toDomain(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.domain.model.SessionStatus.class)
    void everyDomainSessionStatusMapsBothWays(eu.sovereigntylens.domain.model.SessionStatus value) {
      var mapped = GraphMapper.toContract(value);

      assertThat(mapped.wireValue()).isEqualTo(value.wireValue());
      assertThat(GraphMapper.toDomain(mapped)).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.contract.SessionStatus.class)
    void everyContractSessionStatusHasADomainTwin(eu.sovereigntylens.contract.SessionStatus value) {
      assertThat(GraphMapper.toContract(GraphMapper.toDomain(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.domain.model.DependencyStatus.class)
    void everyDomainDependencyStatusMapsBothWays(
        eu.sovereigntylens.domain.model.DependencyStatus value) {
      var mapped = GraphMapper.toContract(value);

      assertThat(mapped.wireValue()).isEqualTo(value.wireValue());
      assertThat(GraphMapper.toDomain(mapped)).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.contract.DependencyStatus.class)
    void everyContractDependencyStatusHasADomainTwin(
        eu.sovereigntylens.contract.DependencyStatus value) {
      assertThat(GraphMapper.toContract(GraphMapper.toDomain(value))).isEqualTo(value);
    }

    @Test
    void wireValuesAreSnakeCaseAsTheContractRequires() {
      assertThat(
              wireValues(
                      eu.sovereigntylens.contract.Jurisdiction.values(),
                      eu.sovereigntylens.contract.Jurisdiction::wireValue)
                  .values())
          .allMatch(value -> value.matches("[a-z]+(_[a-z]+)*"));
    }

    private static <E extends Enum<E>> Map<String, String> wireValues(
        E[] values, Function<E, String> wireValue) {
      Map<String, String> byName = new LinkedHashMap<>();
      Arrays.stream(values).forEach(value -> byName.put(value.name(), wireValue.apply(value)));
      return byName;
    }
  }

  @Nested
  @DisplayName("external exposure")
  class ExternalExposure {

    @Test
    void countsExactlyTheThreeExternalJurisdictions() {
      assertThat(eu.sovereigntylens.domain.model.Jurisdiction.values())
          .filteredOn(eu.sovereigntylens.domain.model.Jurisdiction::isExternal)
          .containsExactly(
              eu.sovereigntylens.domain.model.Jurisdiction.UNITED_STATES,
              eu.sovereigntylens.domain.model.Jurisdiction.CHINA,
              eu.sovereigntylens.domain.model.Jurisdiction.OTHER_EXTERNAL);
    }

    @Test
    void treatsUnknownAsUnresolvedRatherThanEuropean() {
      // Counting an unresolved jurisdiction as exposure would overstate the finding on stage.
      assertThat(eu.sovereigntylens.domain.model.Jurisdiction.UNKNOWN.isExternal()).isFalse();
      assertThat(eu.sovereigntylens.domain.model.Jurisdiction.EUROPE.isExternal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(eu.sovereigntylens.domain.model.Jurisdiction.class)
    void agreesWithTheContractTwinOnWhatCountsAsExternal(
        eu.sovereigntylens.domain.model.Jurisdiction value) {
      assertThat(GraphMapper.toContract(value).isExternal()).isEqualTo(value.isExternal());
    }
  }
}
