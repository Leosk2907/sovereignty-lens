"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import cytoscape, {
  type Core,
  type EdgeSingular,
  type ElementDefinition,
  type NodeSingular,
  type StylesheetCSS,
} from "cytoscape";
import type { GraphEdge, GraphNode, GraphSnapshot, Jurisdiction } from "@/lib/contracts";
import { analyzeGraph } from "@/lib/graph-analysis";

type JurisdictionGroup = "government" | "europe" | "external" | "unknown";

const groupColors: Record<JurisdictionGroup, string> = {
  government: "#d7e7df",
  europe: "#5798ba",
  external: "#d9a45b",
  unknown: "#6f818b",
};

const jurisdictionLabels: Record<Jurisdiction, string> = {
  europe: "Europe",
  united_states: "United States",
  china: "China",
  other_external: "Other external",
  unknown: "Unknown",
};

export function jurisdictionGroup(jurisdiction: Jurisdiction): JurisdictionGroup {
  if (jurisdiction === "europe") return "europe";
  if (jurisdiction === "unknown") return "unknown";
  return "external";
}

export function graphNodeGroup(node: Pick<GraphNode, "organizationType" | "jurisdiction">): JurisdictionGroup {
  return node.organizationType === "government" ? "government" : jurisdictionGroup(node.jurisdiction);
}

interface GraphCanvasProps {
  snapshot: GraphSnapshot;
  revealPath: string[];
  latestEdgeId: string | null;
  compact?: boolean;
}

function nodeData(node: GraphNode, rootId: string) {
  const group = graphNodeGroup(node);
  const isRoot = node.id === rootId;
  return {
    id: node.id,
    label: node.name,
    detailLabel: `${node.name}\n${node.organizationType} · ${jurisdictionLabels[node.jurisdiction]}`,
    kind: node.organizationType,
    jurisdiction: node.jurisdiction,
    group,
    color: groupColors[group],
    root: isRoot ? "yes" : "no",
    seed: node.isSeed ? "yes" : "no",
  };
}

function edgeData(edge: GraphEdge) {
  return {
    id: edge.id,
    source: edge.sourceOrganizationId,
    target: edge.targetOrganizationId,
    seed: edge.isSeed ? "yes" : "no",
  };
}

function graphElements(snapshot: GraphSnapshot): ElementDefinition[] {
  return [
    ...snapshot.nodes.map((node) => ({ data: nodeData(node, snapshot.session.rootOrganizationId) })),
    ...snapshot.edges.map((edge) => ({ data: edgeData(edge) })),
  ];
}

function graphStyles(compact: boolean): StylesheetCSS[] {
  return [
    {
      selector: "node",
      css: {
        "background-color": "data(color)",
        "border-width": 2,
        "border-color": "#d8e8ed",
        "border-opacity": 0.28,
        color: "#edf5f7",
        label: "data(label)",
        "font-family": "var(--font-geist-sans), Geist, sans-serif",
        "font-size": compact ? 9 : 11,
        "font-weight": 540,
        "text-wrap": "wrap",
        "text-max-width": compact ? "106px" : "150px",
        "text-valign": "bottom",
        "text-margin-y": compact ? 7 : 10,
        "text-background-color": "#07141d",
        "text-background-opacity": 0.82,
        "text-background-padding": "3px",
        width: compact ? 28 : 38,
        height: compact ? 28 : 38,
        "transition-property": "width height border-width border-color opacity",
        "transition-duration": 180,
      },
    },
    {
      selector: 'node[root = "yes"]',
      css: {
        "border-color": "#ffffff",
        "border-opacity": 0.72,
        width: compact ? 42 : 54,
        height: compact ? 42 : 54,
        color: "#edf5f7",
        "font-size": compact ? 9 : 12,
      },
    },
    {
      selector: "edge",
      css: {
        width: 1.5,
        "line-color": "#36505d",
        "target-arrow-color": "#68808b",
        "target-arrow-shape": "triangle",
        "curve-style": "round-taxi",
        "taxi-direction": "rightward",
        "taxi-turn": "50%",
        "taxi-radius": 9,
        "arrow-scale": 0.72,
        opacity: 0.82,
        "line-cap": "round",
        "transition-property": "width line-color target-arrow-color opacity",
        "transition-duration": 160,
      },
    },
    {
      selector: ".dimmed",
      css: { opacity: 0.12 },
    },
    {
      selector: "node.path-node",
      css: {
        opacity: 1,
        "border-color": "#f0c274",
        "border-opacity": 0.9,
        "border-width": 4,
      },
    },
    {
      selector: "edge.path-edge",
      css: {
        opacity: 1,
        width: 3,
        "line-color": "#d9a45b",
        "target-arrow-color": "#f0c274",
        "line-style": "dashed",
        "line-dash-pattern": [7, 5],
      },
    },
    {
      selector: "node.focus-node",
      css: {
        label: "data(detailLabel)",
        width: compact ? 44 : 58,
        height: compact ? 44 : 58,
        "border-width": 4,
        "border-color": "#edf5f7",
        "border-opacity": 1,
        "text-wrap": "wrap",
        "text-max-width": compact ? "130px" : "170px",
        "font-size": compact ? 9 : 11,
        "text-background-opacity": 0.96,
        "text-background-padding": "5px",
        "z-index": 30,
      },
    },
    {
      selector: "edge.edge-hover",
      css: {
        opacity: 1,
        width: 3,
        "line-color": "#78b2cf",
        "target-arrow-color": "#a8d0e3",
        "line-style": "dashed",
        "line-dash-pattern": [7, 5],
      },
    },
    {
      selector: "node.latest-node",
      css: {
        "border-color": "#c8e7f4",
        "border-width": 5,
        "border-opacity": 1,
      },
    },
    {
      selector: "edge.latest-edge",
      css: {
        opacity: 1,
        width: 3,
        "line-color": "#68a9c9",
        "target-arrow-color": "#b3dceb",
        "line-style": "dashed",
        "line-dash-pattern": [8, 5],
      },
    },
    {
      selector: "edge.path-edge.latest-edge",
      css: {
        "line-color": "#d9a45b",
        "target-arrow-color": "#f0c274",
      },
    },
    {
      selector: "node.path-node.latest-node",
      css: { "border-color": "#f0c274" },
    },
    {
      selector: ".entering",
      css: { opacity: 0 },
    },
  ];
}

export function GraphCanvas({ snapshot, revealPath, latestEdgeId, compact = false }: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Core | null>(null);
  const initialSnapshotRef = useRef(snapshot);
  const topologyRef = useRef("");
  const layoutTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resizeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const animatedLatestRef = useRef<string | null>(null);
  const reduceMotion = useReducedMotion();
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);
  const [edgeDetail, setEdgeDetail] = useState<string | null>(null);

  const analysis = useMemo(
    () => analyzeGraph(snapshot.nodes, snapshot.edges, snapshot.session.rootOrganizationId),
    [snapshot],
  );
  const focusedNodeId = selectedNodeId ?? hoveredNodeId;
  const interactionPath = focusedNodeId ? analysis.shortestPathTo(focusedNodeId) : [];
  const activePath = revealPath.length > 1 ? revealPath : interactionPath;

  useEffect(() => {
    if (!containerRef.current) return;
    const initial = initialSnapshotRef.current;
    const graph = cytoscape({
      container: containerRef.current,
      elements: graphElements(initial),
      style: graphStyles(compact),
      minZoom: 0.3,
      maxZoom: 2.3,
      wheelSensitivity: 0.18,
      selectionType: "single",
      layout: {
        name: "breadthfirst",
        directed: true,
        direction: "rightward",
        padding: compact ? 30 : 54,
        spacingFactor: compact ? 1.02 : 1.2,
        nodeDimensionsIncludeLabels: true,
        animate: false,
      },
    });
    graphRef.current = graph;

    const describeEdge = (edge: EdgeSingular) => {
      const source = edge.source().data("label") as string;
      const target = edge.target().data("label") as string;
      return `${source} depends on ${target}`;
    };
    const animateEdge = (edge: EdgeSingular) => {
      if (reduceMotion) return;
      edge.stop(true, false);
      edge.style("line-dash-offset", 18);
      edge.animate({ style: { "line-dash-offset": -18 } }, { duration: 620, easing: "ease-out" });
    };

    graph.on("mouseover", "node", (event) => setHoveredNodeId((event.target as NodeSingular).id()));
    graph.on("mouseout", "node", () => setHoveredNodeId(null));
    graph.on("tap", "node", (event) => {
      const id = (event.target as NodeSingular).id();
      setSelectedNodeId((current) => current === id ? null : id);
    });
    graph.on("tap", (event) => {
      if (event.target === graph) setSelectedNodeId(null);
    });
    graph.on("mouseover", "edge", (event) => {
      const edge = event.target as EdgeSingular;
      edge.addClass("edge-hover");
      setEdgeDetail(describeEdge(edge));
      animateEdge(edge);
    });
    graph.on("mouseout", "edge", (event) => {
      (event.target as EdgeSingular).removeClass("edge-hover");
      setEdgeDetail(null);
    });

    const resizeObserver = new ResizeObserver(() => {
      if (resizeTimerRef.current) clearTimeout(resizeTimerRef.current);
      resizeTimerRef.current = setTimeout(() => {
        graph.resize();
        graph.fit(undefined, compact ? 30 : 52);
      }, 90);
    });
    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      if (layoutTimerRef.current) clearTimeout(layoutTimerRef.current);
      if (resizeTimerRef.current) clearTimeout(resizeTimerRef.current);
      graph.destroy();
      graphRef.current = null;
    };
  }, [compact, reduceMotion]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    const nodeIds = new Set(snapshot.nodes.map((node) => node.id));
    const edgeIds = new Set(snapshot.edges.map((edge) => edge.id));
    const topology = `${[...nodeIds].sort().join(",")}|${[...edgeIds].sort().join(",")}`;

    graph.startBatch();
    graph.edges().forEach((edge) => {
      if (!edgeIds.has(edge.id())) graph.remove(edge);
    });
    graph.nodes().forEach((node) => {
      if (!nodeIds.has(node.id())) graph.remove(node);
    });

    for (const node of snapshot.nodes) {
      const existing = graph.getElementById(node.id);
      if (existing.length) {
        existing.data(nodeData(node, snapshot.session.rootOrganizationId));
      } else {
        const latestEdge = snapshot.edges.find((edge) => edge.id === latestEdgeId && edge.targetOrganizationId === node.id);
        const sourcePosition = latestEdge ? graph.getElementById(latestEdge.sourceOrganizationId).position() : { x: 0, y: 0 };
        const addedNode = graph.add({
          group: "nodes",
          classes: topologyRef.current ? "entering" : "",
          data: nodeData(node, snapshot.session.rootOrganizationId),
        });
        addedNode.position(sourcePosition);
      }
    }
    for (const edge of snapshot.edges) {
      const existing = graph.getElementById(edge.id);
      if (existing.length) existing.data(edgeData(edge));
      else graph.add({ group: "edges", classes: topologyRef.current ? "entering" : "", data: edgeData(edge) });
    }
    graph.endBatch();

    if (topology !== topologyRef.current) {
      const isInitialLayout = topologyRef.current === "";
      topologyRef.current = topology;
      if (layoutTimerRef.current) clearTimeout(layoutTimerRef.current);

      const runLayout = () => {
        const layoutOptions = {
          name: "breadthfirst",
          directed: true,
          direction: "rightward",
          roots: graph.getElementById(snapshot.session.rootOrganizationId),
          padding: compact ? 30 : 54,
          spacingFactor: compact ? 1.02 : 1.2,
          nodeDimensionsIncludeLabels: true,
        } as const;

        graph.stop();
        graph.nodes().stop();
        if (isInitialLayout || reduceMotion) {
          graph.layout({ ...layoutOptions, fit: true, animate: false }).run();
          graph.elements(".entering").removeClass("entering");
          return;
        }

        const currentPositions = new Map(
          graph.nodes().map((node) => [node.id(), { ...node.position() }] as const),
        );
        const currentZoom = graph.zoom();
        const currentPan = { ...graph.pan() };

        graph.layout({ ...layoutOptions, fit: false, animate: false }).run();
        const targetPositions = new Map(
          graph.nodes().map((node) => [node.id(), { ...node.position() }] as const),
        );
        graph.fit(graph.elements(), compact ? 30 : 54);
        const targetZoom = graph.zoom();
        const targetPan = { ...graph.pan() };

        graph.nodes().forEach((node) => {
          node.position(currentPositions.get(node.id()) ?? node.position());
        });
        graph.zoom(currentZoom);
        graph.pan(currentPan);
        graph.elements(".entering").removeClass("entering");

        graph.nodes().forEach((node) => {
          const position = targetPositions.get(node.id());
          if (position) node.animate({ position }, { duration: 560, easing: "ease-out-cubic" });
        });
        graph.animate(
          { zoom: targetZoom, pan: targetPan },
          { duration: 560, easing: "ease-out-cubic" },
        );
      };

      if (isInitialLayout || reduceMotion) runLayout();
      else layoutTimerRef.current = setTimeout(runLayout, 140);
    }
  }, [snapshot, latestEdgeId, compact, reduceMotion]);

  useEffect(() => {
    if (selectedNodeId && !snapshot.nodes.some((node) => node.id === selectedNodeId)) setSelectedNodeId(null);
  }, [selectedNodeId, snapshot.nodes]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    graph.elements().removeClass("dimmed path-node path-edge focus-node");

    if (activePath.length > 1) {
      graph.elements().addClass("dimmed");
      for (const nodeId of activePath) graph.getElementById(nodeId).removeClass("dimmed").addClass("path-node");
      for (let index = 1; index < activePath.length; index += 1) {
        const source = activePath[index - 1];
        const target = activePath[index];
        graph.edges(`[source = "${source}"][target = "${target}"]`).removeClass("dimmed").addClass("path-edge");
      }
    }

    if (focusedNodeId) graph.getElementById(focusedNodeId).addClass("focus-node").removeClass("dimmed");

    if (activePath.length > 1 && !reduceMotion) {
      graph.edges(".path-edge").forEach((edge) => {
        edge.stop(true, false);
        edge.style("line-dash-offset", 18);
        edge.animate({ style: { "line-dash-offset": -18 } }, { duration: 680, easing: "ease-out" });
      });
    }
  }, [activePath, focusedNodeId, reduceMotion]);

  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    graph.elements().removeClass("latest-edge latest-node");
    if (!latestEdgeId) return;
    const edge = graph.getElementById(latestEdgeId);
    if (!edge.length || !edge.isEdge()) return;
    edge.addClass("latest-edge");
    edge.target().addClass("latest-node");
    if (animatedLatestRef.current !== latestEdgeId && !reduceMotion) {
      animatedLatestRef.current = latestEdgeId;
      edge.style("line-dash-offset", 24);
      edge.animate({ style: { "line-dash-offset": -24 } }, { duration: 760, easing: "ease-out" });
    }
  }, [latestEdgeId, snapshot, reduceMotion]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSelectedNodeId(null);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <div className="graph-canvas-shell">
      <div ref={containerRef} className="graph-canvas" aria-label="Interactive dependency graph" role="img" />
      <AnimatePresence>
        {edgeDetail && (
          <motion.div
            className="edge-readout"
            initial={{ opacity: 0, y: 5 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.16 }}
          >
            {edgeDetail}
          </motion.div>
        )}
      </AnimatePresence>
      <span className="sr-only" aria-live="polite">{edgeDetail}</span>
    </div>
  );
}
