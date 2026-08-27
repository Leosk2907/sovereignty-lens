"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { AdminControls } from "@/components/admin-controls";
import { Brand } from "@/components/brand";
import { GraphCanvas } from "@/components/graph-canvas";
import { useLiveGraph } from "@/hooks/use-live-graph";
import { analyzeGraph } from "@/lib/graph-analysis";

interface LiveGraphExperienceProps {
  mode?: "public" | "admin";
  onAdminLogout?: () => void;
}

export function LiveGraphExperience({ mode = "public", onAdminLogout }: LiveGraphExperienceProps) {
  const live = useLiveGraph();
  const [contributionUrl, setContributionUrl] = useState("/contribute");

  useEffect(() => {
    setContributionUrl(`${window.location.origin}/contribute`);
  }, []);

  const analysis = useMemo(() => {
    if (!live.snapshot) return null;
    return analyzeGraph(live.snapshot.nodes, live.snapshot.edges, live.snapshot.session.rootOrganizationId);
  }, [live.snapshot]);

  if (live.loading && !live.snapshot) {
    return <main className="center-state"><div className="spinner" /><p>Building the dependency map…</p></main>;
  }

  if (!live.snapshot || !analysis) {
    return (
      <main className="center-state error-state">
        <Brand />
        <h1>The graph could not be loaded.</h1>
        <p>{live.error ?? "Please check the connection and try again."}</p>
        <button className="button primary" onClick={() => void live.refresh()}>Try again</button>
      </main>
    );
  }

  const latestEdge = live.snapshot.edges.find((edge) => edge.id === live.latestEdgeId);
  const latestTarget = live.snapshot.nodes.find((node) => node.id === latestEdge?.targetOrganizationId);
  const revealedTarget = live.snapshot.nodes.find((node) => node.id === live.revealPath.at(-1));
  const graphArea = (
    <section className="graph-stage">
      <header className="presentation-header">
        <Brand />
        <div className="presentation-actions">
          <span className={`connection-pill ${live.connection}`}><i />{live.connection}</span>
          {mode === "public" ? (
            <>
              <Link href="/about">About</Link>
              <Link className="admin-entry" href="/admin">Admin</Link>
            </>
          ) : (
            <Link href="/" target="_blank">Open public view ↗</Link>
          )}
        </div>
      </header>

      <div className="graph-heading">
        <div>
          <span className="eyebrow">Live sovereignty map · Round {live.snapshot.session.currentRound}</span>
          <h1>How far does dependency travel?</h1>
          <p>Every arrow reads: <strong>this organization depends on the next.</strong></p>
        </div>
        <div className="metric-row" aria-label="Graph metrics">
          <Metric value={live.snapshot.nodes.length} label="organizations" />
          <Metric value={live.snapshot.edges.filter((edge) => !edge.isSeed).length} label="audience links" />
          <Metric value={analysis.reachableExternalIds.size} label="external" danger={analysis.reachableExternalIds.size > 0} />
          <Metric value={analysis.maximumDepth} label="max depth" />
        </div>
      </div>

      <div className="graph-frame">
        <GraphCanvas snapshot={live.snapshot} revealPath={live.revealPath} latestEdgeId={live.latestEdgeId} compact={mode === "admin"} />
        <div className="legend" aria-label="Jurisdiction legend">
          <Legend color="blue" label="Europe" />
          <Legend color="red" label="United States" />
          <Legend color="orange" label="China" />
          <Legend color="purple" label="Other external" />
          <Legend color="gray" label="Unknown" />
        </div>
        {mode === "public" && (
          <div className="qr-card">
            <QRCodeSVG value={contributionUrl} size={92} bgColor="#ffffff" fgColor="#07111f" level="M" />
            <div><strong>Add one dependency</strong><span>Scan to grow the live map</span></div>
          </div>
        )}
      </div>

      {revealedTarget && live.revealPath.length > 1 && (
        <div className="reveal-banner" role="status">
          <span>Hidden dependency revealed</span>
          <strong>{live.revealPath.length - 1} steps to {revealedTarget.name}</strong>
        </div>
      )}
      {latestTarget && !revealedTarget && (
        <div className="latest-toast" role="status">New dependency: <strong>{latestTarget.name}</strong></div>
      )}
      <footer className="demo-disclaimer">Simulated, audience-submitted demo data — not a factual claim.</footer>
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

function Metric({ value, label, danger = false }: { value: number; label: string; danger?: boolean }) {
  return <div className={`metric ${danger ? "danger" : ""}`}><strong>{value}</strong><span>{label}</span></div>;
}

function Legend({ color, label }: { color: string; label: string }) {
  return <span><i className={color} />{label}</span>;
}
