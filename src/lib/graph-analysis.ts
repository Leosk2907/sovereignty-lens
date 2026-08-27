import type { GraphEdge, GraphNode, Jurisdiction } from "@/lib/contracts";

const EXTERNAL_JURISDICTIONS = new Set<Jurisdiction>([
  "united_states",
  "china",
  "other_external",
]);

export interface GraphAnalysis {
  reachableIds: Set<string>;
  reachableExternalIds: Set<string>;
  predecessor: Map<string, string>;
  depth: Map<string, number>;
  maximumDepth: number;
  shortestPathTo: (targetId: string) => string[];
}

export interface DependencyGraphSummary {
  organizationCount: number;
  governmentCount: number;
  europeanCompanyCount: number;
  dependencyCount: number;
  directDependencyCount: number;
  reachableDependencyCount: number;
  reachableEuropeanCompanyCount: number;
  reachableExternalCount: number;
  maximumDepth: number;
}

export function analyzeGraph(
  nodes: GraphNode[],
  edges: GraphEdge[],
  rootId: string,
): GraphAnalysis {
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const outgoing = new Map<string, string[]>();

  for (const edge of edges) {
    if (edge.status !== "active") continue;
    const targets = outgoing.get(edge.sourceOrganizationId) ?? [];
    targets.push(edge.targetOrganizationId);
    outgoing.set(edge.sourceOrganizationId, targets);
  }

  const reachableIds = new Set<string>();
  const predecessor = new Map<string, string>();
  const depth = new Map<string, number>();
  const queue: string[] = [];

  if (nodeById.has(rootId)) {
    reachableIds.add(rootId);
    depth.set(rootId, 0);
    queue.push(rootId);
  }

  let cursor = 0;
  let maximumDepth = 0;

  while (cursor < queue.length) {
    const sourceId = queue[cursor++];
    const sourceDepth = depth.get(sourceId) ?? 0;
    for (const targetId of outgoing.get(sourceId) ?? []) {
      if (!nodeById.has(targetId) || reachableIds.has(targetId)) continue;
      reachableIds.add(targetId);
      predecessor.set(targetId, sourceId);
      const targetDepth = sourceDepth + 1;
      depth.set(targetId, targetDepth);
      maximumDepth = Math.max(maximumDepth, targetDepth);
      queue.push(targetId);
    }
  }

  const reachableExternalIds = new Set(
    nodes
      .filter(
        (node) =>
          reachableIds.has(node.id) && EXTERNAL_JURISDICTIONS.has(node.jurisdiction),
      )
      .map((node) => node.id),
  );

  function shortestPathTo(targetId: string): string[] {
    if (!reachableIds.has(targetId)) return [];
    const path = [targetId];
    let current = targetId;
    while (current !== rootId) {
      const parent = predecessor.get(current);
      if (!parent) return [];
      path.push(parent);
      current = parent;
    }
    return path.reverse();
  }

  return {
    reachableIds,
    reachableExternalIds,
    predecessor,
    depth,
    maximumDepth,
    shortestPathTo,
  };
}

export function summarizeDependencyGraph(
  nodes: GraphNode[],
  edges: GraphEdge[],
  rootId: string,
): DependencyGraphSummary {
  const analysis = analyzeGraph(nodes, edges, rootId);
  const activeEdges = edges.filter((edge) => edge.status === "active");
  const reachableNodes = nodes.filter((node) => analysis.reachableIds.has(node.id));

  return {
    organizationCount: nodes.length,
    governmentCount: nodes.filter((node) => node.organizationType === "government").length,
    europeanCompanyCount: nodes.filter(
      (node) => node.jurisdiction === "europe" && node.organizationType !== "government",
    ).length,
    dependencyCount: activeEdges.length,
    directDependencyCount: activeEdges.filter((edge) => edge.sourceOrganizationId === rootId).length,
    reachableDependencyCount: Math.max(analysis.reachableIds.size - 1, 0),
    reachableEuropeanCompanyCount: reachableNodes.filter(
      (node) => node.id !== rootId
        && node.jurisdiction === "europe"
        && node.organizationType !== "government",
    ).length,
    reachableExternalCount: analysis.reachableExternalIds.size,
    maximumDepth: analysis.maximumDepth,
  };
}
