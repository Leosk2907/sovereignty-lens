"use client";

import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
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
    return <main className="center-state"><div className="spinner" /><p>Loading the dependency network…</p></main>;
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

  const revealedTarget = live.snapshot.nodes.find((node) => node.id === live.revealPath.at(-1));
  const graphArea = (
    <section className="graph-stage">
      <header className="presentation-header">
        <Brand active={Boolean(live.latestEdgeId)} />
        <span className={`connection-pill ${live.connection}`}><i />{live.connection}</span>
      </header>

      <div className="graph-frame">
        <GraphCanvas
          snapshot={live.snapshot}
          revealPath={live.revealPath}
          latestEdgeId={live.latestEdgeId}
          compact={mode === "admin"}
        />

        <div className="graph-question">
          <h1>What does Europe depend on?</h1>
        </div>

        <div className="exposure-count" aria-label={`${analysis.reachableExternalIds.size} external dependencies revealed`}>
          <strong>{analysis.reachableExternalIds.size}</strong>
          <span>external dependencies<br />revealed</span>
        </div>

        <div className="micro-key" aria-label="Jurisdiction legend">
          <Legend color="europe" label="Europe" />
          <Legend color="external" label="External" />
          <Legend color="unknown" label="Unknown" />
        </div>

        {mode === "public" && (
          <div className="qr-dock">
            <QRCodeSVG value={contributionUrl} size={86} bgColor="#edf5f7" fgColor="#07141d" level="M" />
            <div><span>Join the live map</span><strong>Add your company</strong></div>
          </div>
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

        <div className="demo-disclaimer">Simulated, audience-submitted demo data — not a factual claim.</div>
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

function Legend({ color, label }: { color: "europe" | "external" | "unknown"; label: string }) {
  return <span><i className={color} />{label}</span>;
}
