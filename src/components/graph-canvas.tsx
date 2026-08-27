"use client";

import { useEffect, useRef } from "react";
import cytoscape, { type Core, type ElementDefinition, type StylesheetCSS } from "cytoscape";
import type { GraphSnapshot } from "@/lib/contracts";

const jurisdictionColors: Record<string, string> = {
  europe: "#2366f2",
  united_states: "#f04f5f",
  china: "#ff8a3d",
  other_external: "#a970ff",
  unknown: "#7d8b9e",
};

interface GraphCanvasProps {
  snapshot: GraphSnapshot;
  revealPath: string[];
  latestEdgeId: string | null;
  compact?: boolean;
}

export function GraphCanvas({ snapshot, revealPath, latestEdgeId, compact = false }: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Core | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    const revealNodeIds = new Set(revealPath);
    const revealPairs = new Set(revealPath.slice(1).map((target, index) => `${revealPath[index]}:${target}`));
    const elements: ElementDefinition[] = [
      ...snapshot.nodes.map((node) => ({
        data: {
          id: node.id,
          label: node.name,
          kind: node.organizationType,
          jurisdiction: node.jurisdiction,
          color: jurisdictionColors[node.jurisdiction],
          root: node.id === snapshot.session.rootOrganizationId ? "yes" : "no",
          seed: node.isSeed ? "yes" : "no",
        },
        classes: revealNodeIds.has(node.id) ? "reveal" : "",
      })),
      ...snapshot.edges.map((edge) => ({
        data: {
          id: edge.id,
          source: edge.sourceOrganizationId,
          target: edge.targetOrganizationId,
          latest: edge.id === latestEdgeId ? "yes" : "no",
        },
        classes: revealPairs.has(`${edge.sourceOrganizationId}:${edge.targetOrganizationId}`) ? "reveal" : "",
      })),
    ];

    const styles: StylesheetCSS[] = [
      {
        selector: "node",
        css: {
          "background-color": "data(color)",
          "border-width": 3,
          "border-color": "#d8e4ff",
          "border-opacity": 0.26,
          color: "#f8fbff",
          label: "data(label)",
          "font-family": "Inter, ui-sans-serif, system-ui",
          "font-size": compact ? 9 : 11,
          "font-weight": 600,
          "text-wrap": "ellipsis",
          "text-max-width": compact ? "78px" : "118px",
          "text-valign": "bottom",
          "text-margin-y": compact ? 6 : 9,
          "text-background-color": "#07111f",
          "text-background-opacity": 0.78,
          "text-background-padding": "3px",
          width: compact ? 31 : 42,
          height: compact ? 31 : 42,
        },
      },
      {
        selector: 'node[root = "yes"]',
        css: {
          "background-color": "#ffc857",
          "border-color": "#fff2c5",
          "border-opacity": 0.8,
          shape: "diamond",
          width: compact ? 42 : 56,
          height: compact ? 42 : 56,
          "font-size": compact ? 10 : 12,
        },
      },
      {
        selector: "edge",
        css: {
          width: 2,
          "line-color": "#526984",
          "target-arrow-color": "#7f96b2",
          "target-arrow-shape": "triangle",
          "curve-style": "bezier",
          "arrow-scale": 0.85,
          opacity: 0.72,
        },
      },
      {
        selector: 'edge[latest = "yes"]',
        css: { "line-color": "#8bb3ff", "target-arrow-color": "#8bb3ff", width: 3, opacity: 1 },
      },
      {
        selector: ".reveal",
        css: {
          "border-color": "#fff3b0",
          "border-width": 6,
          "border-opacity": 1,
          "line-color": "#ffdc73",
          "target-arrow-color": "#ffdc73",
          width: 5,
          opacity: 1,
          "z-index": 20,
        },
      },
    ];

    graphRef.current?.destroy();
    const graph = cytoscape({
      container: containerRef.current,
      elements,
      style: styles,
      minZoom: 0.25,
      maxZoom: 2.2,
      layout: {
        name: "breadthfirst",
        directed: true,
        roots: [snapshot.session.rootOrganizationId],
        padding: compact ? 22 : 48,
        spacingFactor: compact ? 0.9 : 1.18,
        animate: false,
      },
    });
    graphRef.current = graph;
    graph.fit(undefined, compact ? 22 : 44);

    return () => {
      graph.destroy();
      graphRef.current = null;
    };
  }, [snapshot, revealPath, latestEdgeId, compact]);

  return <div ref={containerRef} className="graph-canvas" aria-label="Live dependency graph" role="img" />;
}
