import { describe, expect, it } from "vitest";
import type { GraphEdge, GraphNode } from "@/lib/contracts";
import { analyzeGraph, summarizeDependencyGraph } from "@/lib/graph-analysis";

const nodes: GraphNode[] = [
  { id: "00000000-0000-4000-8000-000000000001", name: "Root", organizationType: "government", jurisdiction: "europe", isSeed: true },
  { id: "00000000-0000-4000-8000-000000000002", name: "Middle", organizationType: "software", jurisdiction: "europe", isSeed: false },
  { id: "00000000-0000-4000-8000-000000000003", name: "External", organizationType: "cloud", jurisdiction: "united_states", isSeed: false },
  { id: "00000000-0000-4000-8000-000000000004", name: "Unknown", organizationType: "other", jurisdiction: "unknown", isSeed: false },
];

function edge(id: string, sourceOrganizationId: string, targetOrganizationId: string): GraphEdge {
  return { id, sourceOrganizationId, targetOrganizationId, isSeed: false, status: "active", createdAt: "2026-08-27T08:00:00.000Z" };
}

describe("analyzeGraph", () => {
  it("finds the shortest external dependency path", () => {
    const analysis = analyzeGraph(nodes, [
      edge("00000000-0000-4000-8000-000000000101", nodes[0].id, nodes[1].id),
      edge("00000000-0000-4000-8000-000000000102", nodes[1].id, nodes[2].id),
    ], nodes[0].id);

    expect(analysis.shortestPathTo(nodes[2].id)).toEqual([nodes[0].id, nodes[1].id, nodes[2].id]);
    expect(analysis.reachableExternalIds).toEqual(new Set([nodes[2].id]));
    expect(analysis.maximumDepth).toBe(2);
  });

  it("handles cycles and does not classify unknown as external", () => {
    const analysis = analyzeGraph(nodes, [
      edge("00000000-0000-4000-8000-000000000103", nodes[0].id, nodes[3].id),
      edge("00000000-0000-4000-8000-000000000104", nodes[3].id, nodes[0].id),
    ], nodes[0].id);

    expect(analysis.reachableIds.size).toBe(2);
    expect(analysis.reachableExternalIds.size).toBe(0);
  });

  it("summarizes global and government dependency statistics", () => {
    const edges = [
      edge("00000000-0000-4000-8000-000000000105", nodes[0].id, nodes[1].id),
      edge("00000000-0000-4000-8000-000000000106", nodes[1].id, nodes[2].id),
      edge("00000000-0000-4000-8000-000000000107", nodes[1].id, nodes[3].id),
    ];
    const summary = summarizeDependencyGraph(nodes, edges, nodes[0].id);

    expect(summary).toMatchObject({
      organizationCount: 4,
      governmentCount: 1,
      europeanCompanyCount: 1,
      dependencyCount: 3,
      directDependencyCount: 1,
      reachableDependencyCount: 3,
      reachableEuropeanCompanyCount: 1,
      reachableExternalCount: 1,
      maximumDepth: 2,
    });
  });
});
