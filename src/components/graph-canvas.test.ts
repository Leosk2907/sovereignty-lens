import { describe, expect, it } from "vitest";
import { graphNodeGroup, groupSovereigntyZones, jurisdictionGroup } from "@/components/graph-canvas";
import { populatedDemoGraphFixture } from "@/lib/fixtures";

describe("graph visual grouping", () => {
  it("uses one coherent group for every external jurisdiction", () => {
    expect(jurisdictionGroup("united_states")).toBe("external");
    expect(jurisdictionGroup("china")).toBe("external");
    expect(jurisdictionGroup("other_external")).toBe("external");
  });

  it("keeps only Europe and external visual jurisdiction groups", () => {
    expect(jurisdictionGroup("europe")).toBe("europe");
    expect(jurisdictionGroup("unknown")).toBe("external");
  });

  it("separates governmental bodies from European companies", () => {
    expect(graphNodeGroup({ organizationType: "government", jurisdiction: "europe" })).toBe("government");
    expect(graphNodeGroup({ organizationType: "software", jurisdiction: "europe" })).toBe("europe");
  });

  it("places government, Europe, and external nodes in loose left-to-right zones", () => {
    const basePositions = new Map(
      populatedDemoGraphFixture.nodes.map((node, index) => [
        node.id,
        { x: (index % 5) * 180, y: index * 48 },
      ]),
    );
    const grouped = groupSovereigntyZones(populatedDemoGraphFixture.nodes, basePositions);
    const externalNodes = populatedDemoGraphFixture.nodes.filter((node) => graphNodeGroup(node) === "external");
    const governmentNodes = populatedDemoGraphFixture.nodes.filter((node) => graphNodeGroup(node) === "government");
    const europeanNodes = populatedDemoGraphFixture.nodes.filter((node) => graphNodeGroup(node) === "europe");
    const externalXs = new Set(externalNodes.map((node) => grouped.get(node.id)?.x));
    const europeanXs = new Set(europeanNodes.map((node) => grouped.get(node.id)?.x));
    const rightmostGovernmentX = Math.max(...governmentNodes.map((node) => grouped.get(node.id)?.x ?? 0));
    const leftmostEuropeanX = Math.min(...europeanNodes.map((node) => grouped.get(node.id)?.x ?? 0));
    const rightmostEuropeanX = Math.max(...europeanNodes.map((node) => grouped.get(node.id)?.x ?? 0));
    const leftmostExternalX = Math.min(...externalNodes.map((node) => grouped.get(node.id)?.x ?? 0));

    expect(rightmostGovernmentX).toBeLessThan(leftmostEuropeanX);
    expect(rightmostEuropeanX).toBeLessThan(leftmostExternalX);
    expect(europeanXs.size).toBeGreaterThan(3);
    expect(externalXs.size).toBeGreaterThan(1);
  });

  it("exercises the layout with a dense European dependency network", () => {
    const nodesById = new Map(populatedDemoGraphFixture.nodes.map((node) => [node.id, node]));
    const internalCompanyEdges = populatedDemoGraphFixture.edges.filter((edge) => {
      const source = nodesById.get(edge.sourceOrganizationId);
      const target = nodesById.get(edge.targetOrganizationId);
      return source?.organizationType !== "government"
        && source?.jurisdiction === "europe"
        && target?.organizationType !== "government"
        && target?.jurisdiction === "europe";
    });

    expect(internalCompanyEdges.length).toBeGreaterThanOrEqual(20);
  });
});
