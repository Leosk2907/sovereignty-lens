"use client";

import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { QRCodeSVG } from "qrcode.react";
import { AdminControls } from "@/components/admin-controls";
import { Brand } from "@/components/brand";
import { GraphCanvas } from "@/components/graph-canvas";
import { useLiveGraph } from "@/hooks/use-live-graph";
import {
  analyzeGraph,
  summarizeDependencyGraph,
  type DependencyGraphSummary,
} from "@/lib/graph-analysis";
import type { GraphNode } from "@/lib/contracts";

interface LiveGraphExperienceProps {
  mode?: "public" | "admin";
  onAdminLogout?: () => void;
}

export function LiveGraphExperience({ mode = "public", onAdminLogout }: LiveGraphExperienceProps) {
  const live = useLiveGraph();
  const [contributionUrl, setContributionUrl] = useState("/contribute");
  const [hoveredGovernmentId, setHoveredGovernmentId] = useState<string | null>(null);
  const [selectedGovernmentId, setSelectedGovernmentId] = useState<string | null>(null);

  useEffect(() => {
    setContributionUrl(`${window.location.origin}/contribute`);
  }, []);

  const analysis = useMemo(() => {
    if (!live.snapshot) return null;
    return analyzeGraph(live.snapshot.nodes, live.snapshot.edges, live.snapshot.session.rootOrganizationId);
  }, [live.snapshot]);

  const globalSummary = useMemo(() => {
    if (!live.snapshot) return null;
    return summarizeDependencyGraph(
      live.snapshot.nodes,
      live.snapshot.edges,
      live.snapshot.session.rootOrganizationId,
    );
  }, [live.snapshot]);

  const dashboardGovernmentId = hoveredGovernmentId ?? selectedGovernmentId;
  const dashboardGovernment = live.snapshot?.nodes.find(
    (node) => node.id === dashboardGovernmentId && node.organizationType === "government",
  ) ?? null;
  const governmentSummary = useMemo(() => {
    if (!live.snapshot || !dashboardGovernment) return null;
    return summarizeDependencyGraph(
      live.snapshot.nodes,
      live.snapshot.edges,
      dashboardGovernment.id,
    );
  }, [dashboardGovernment, live.snapshot]);

  useEffect(() => {
    if (!live.snapshot) return;
    const governmentIds = new Set(
      live.snapshot.nodes
        .filter((node) => node.organizationType === "government")
        .map((node) => node.id),
    );
    if (hoveredGovernmentId && !governmentIds.has(hoveredGovernmentId)) setHoveredGovernmentId(null);
    if (selectedGovernmentId && !governmentIds.has(selectedGovernmentId)) setSelectedGovernmentId(null);
  }, [hoveredGovernmentId, live.snapshot, selectedGovernmentId]);

  if (live.loading && !live.snapshot) {
    return <main className="center-state"><div className="spinner" /><p>Loading the dependency network…</p></main>;
  }

  if (!live.snapshot || !analysis || !globalSummary) {
    return (
      <main className="center-state error-state">
        <Brand />
        <h1>The graph could not be loaded.</h1>
        <p>{live.error ?? "Please check the connection and try again."}</p>
        <button className="button primary" onClick={() => void live.refresh()}>Try again</button>
      </main>
    );
  }

  const revealedTarget = live.snapshot.nodes.find((node) => node.id === live.revealPath.at(-1));
  const graphArea = (
    <section className="graph-stage">
      <header className="presentation-header">
        <Brand active={Boolean(live.latestEdgeId)} />
        <span
          className={`connection-pill ${live.connection}`}
          role="status"
          aria-label={`Connection ${live.connection}`}
          title={`Connection ${live.connection}`}
        ><i /></span>
      </header>

      <div className={`graph-frame ${mode === "public" ? "has-stats" : ""}`}>
        <GraphCanvas
          snapshot={live.snapshot}
          revealPath={live.revealPath}
          latestEdgeId={live.latestEdgeId}
          compact={mode === "admin"}
          onGovernmentHover={mode === "public" ? setHoveredGovernmentId : undefined}
          onGovernmentSelect={mode === "public" ? setSelectedGovernmentId : undefined}
        />

        <div className="graph-question">
          <h1>What does Europe depend on?</h1>
        </div>

        {mode === "admin" && (
          <div className="exposure-count" aria-label={`${analysis.reachableExternalIds.size} external dependencies revealed`}>
            <strong>{analysis.reachableExternalIds.size}</strong>
            <span>external dependencies<br />revealed</span>
          </div>
        )}

        <div className="micro-key" aria-label="Jurisdiction legend">
          <Legend color="government" label="Government" />
          <Legend color="europe" label="Europe" />
          <Legend color="external" label="External" />
        </div>

        {mode === "public" && (
          <GraphStatsPanel
            globalSummary={globalSummary}
            government={dashboardGovernment}
            governmentSummary={governmentSummary}
            pinned={Boolean(selectedGovernmentId && !hoveredGovernmentId)}
            contributionUrl={contributionUrl}
          />
        )}

        <AnimatePresence>
          {revealedTarget && live.revealPath.length > 1 && (
            <motion.div
              className="reveal-strip"
              role="status"
              initial={{ opacity: 0, y: 12, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8 }}
              transition={{ duration: 0.32, ease: [0.16, 1, 0.3, 1] }}
            >
              <span>External dependency revealed</span>
              <strong>{live.revealPath.length - 1} steps to {revealedTarget.name}</strong>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="demo-disclaimer">Simulated, audience-submitted demo data. Not a factual claim.</div>
      </div>
    </section>
  );

  if (mode === "admin") {
    return (
      <main className="admin-layout">
        {graphArea}
        <AdminControls
          snapshot={live.snapshot}
          contributionUrl={contributionUrl}
          onRefresh={live.refresh}
          onLogout={() => onAdminLogout?.()}
        />
      </main>
    );
  }

  return <main className="presentation-shell">{graphArea}</main>;
}

function Legend({ color, label }: { color: "government" | "europe" | "external"; label: string }) {
  return <span><i className={color} />{label}</span>;
}

interface GraphStatsPanelProps {
  globalSummary: DependencyGraphSummary;
  government: GraphNode | null;
  governmentSummary: DependencyGraphSummary | null;
  pinned: boolean;
  contributionUrl: string;
}

function GraphStatsPanel({
  globalSummary,
  government,
  governmentSummary,
  pinned,
  contributionUrl,
}: GraphStatsPanelProps) {
  const contextual = Boolean(government && governmentSummary);
  const summary = contextual && governmentSummary ? governmentSummary : globalSummary;
  const stats = contextual
    ? [
        { value: summary.directDependencyCount, label: "Direct dependencies" },
        { value: summary.reachableDependencyCount, label: "Dependencies in reach" },
        { value: summary.reachableEuropeanCompanyCount, label: "European companies" },
        { value: summary.maximumDepth, label: "Dependency layers" },
      ]
    : [
        { value: summary.organizationCount, label: "Organizations mapped" },
        { value: summary.europeanCompanyCount, label: "European companies" },
        { value: summary.dependencyCount, label: "Dependency links" },
        { value: summary.governmentCount, label: "Government bodies" },
      ];

  return (
    <aside className="graph-stats-panel" aria-label="Dependency graph statistics" aria-live="polite">
      <div className="graph-stats-content">
        <AnimatePresence initial={false} mode="wait">
          <motion.div
            className="graph-stats-view"
            key={government?.id ?? "global"}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className="graph-stats-heading">
              <span>{contextual ? "Government body" : "European graph"}</span>
              {contextual && <i>{pinned ? "Pinned" : "Inspecting"}</i>}
            </div>
            <h2>{government?.name ?? "Network overview"}</h2>

            <div
              className="graph-stats-hero"
              aria-label={`${summary.reachableExternalCount} external dependencies revealed`}
            >
              <strong>{summary.reachableExternalCount}</strong>
              <span>External dependencies<br />revealed</span>
            </div>

            <dl className="graph-stats-grid">
              {stats.map((stat) => (
                <div key={stat.label}>
                  <dt>{stat.label}</dt>
                  <dd>{stat.value}</dd>
                </div>
              ))}
            </dl>

            <p className="graph-stats-hint">
              {contextual
                ? pinned
                  ? "Pinned. Click the highlighted body again to return to the whole graph."
                  : "Move away to return to the whole graph. Click the body to pin this view."
                : "Hover a government node to inspect its dependency network. Click to pin it."}
            </p>
          </motion.div>
        </AnimatePresence>
      </div>

      <div className="qr-dock">
        <QRCodeSVG value={contributionUrl} size={86} bgColor="#edf5f7" fgColor="#07141d" level="M" />
        <div><span>Join the live map</span><strong>Add your company</strong></div>
      </div>
    </aside>
  );
}
