"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  getAdminDependencies,
  logoutAdmin,
  runAdminAction,
  setDependencyStatus,
} from "@/lib/api-client";
import type { AdminAction, AdminDependency, GraphSnapshot } from "@/lib/contracts";
import { analyzeGraph } from "@/lib/graph-analysis";

interface AdminControlsProps {
  snapshot: GraphSnapshot;
  contributionUrl: string;
  onRefresh: () => Promise<void>;
  onLogout: () => void;
}

export function AdminControls({ snapshot, contributionUrl, onRefresh, onLogout }: AdminControlsProps) {
  const [dependencies, setDependencies] = useState<AdminDependency[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const analysis = useMemo(
    () => analyzeGraph(snapshot.nodes, snapshot.edges, snapshot.session.rootOrganizationId),
    [snapshot],
  );

  const loadDependencies = useCallback(async () => {
    try {
      const result = await getAdminDependencies();
      setDependencies(result.dependencies);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not load dependencies.");
    }
  }, []);

  useEffect(() => {
    void loadDependencies();
  }, [loadDependencies, snapshot.session.currentRound, snapshot.session.status]);

  async function run(action: AdminAction) {
    setBusy(action.type);
    setMessage(null);
    try {
      await runAdminAction(action);
      await Promise.all([onRefresh(), loadDependencies()]);
      setMessage(action.type === "reset" ? "New round started." : `Action completed: ${action.type}.`);
      setConfirmReset(false);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Action failed.");
    } finally {
      setBusy(null);
    }
  }

  async function changeStatus(id: string, status: "active" | "hidden") {
    setBusy(id);
    setMessage(null);
    try {
      await setDependencyStatus(id, status);
      await Promise.all([onRefresh(), loadDependencies()]);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not update dependency.");
    } finally {
      setBusy(null);
    }
  }

  async function copyUrl(value: string) {
    await navigator.clipboard.writeText(value);
    setMessage("Link copied.");
  }

  return (
    <motion.aside
      className={`admin-panel ${expanded ? "expanded" : "collapsed"}`}
      aria-label="Presenter controls"
      initial={false}
      animate={{ width: expanded ? 360 : 208 }}
      transition={{ duration: 0.28, ease: [0.16, 1, 0.3, 1] }}
    >
      <div className="admin-panel-heading">
        <div>
          <span className="eyebrow">Presenter</span>
          <h2>Round {snapshot.session.currentRound}</h2>
        </div>
        <div className="admin-heading-actions">
          <span className={`status-pill ${snapshot.session.status}`}>{snapshot.session.status}</span>
          <button
            className="inspector-toggle"
            type="button"
            aria-expanded={expanded}
            aria-label={expanded ? "Close presenter inspector" : "Open presenter inspector"}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? "→" : "←"}
          </button>
        </div>
      </div>

      <div className="admin-rail-actions">
        {snapshot.session.status === "open" ? (
          <button className="button warning" disabled={busy !== null} onClick={() => void run({ type: "pause" })}>Pause submissions</button>
        ) : (
          <button className="button primary" disabled={busy !== null} onClick={() => void run({ type: "resume" })}>Resume submissions</button>
        )}
        <button className="button secondary" disabled={busy !== null} onClick={() => void run({ type: "undo" })}>Undo latest</button>
      </div>

      {message && <p className="admin-message" role="status">{message}</p>}

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            className="admin-inspector-content"
            initial={{ opacity: 0, x: 12 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 8 }}
            transition={{ duration: 0.2 }}
          >
            <dl className="admin-metrics">
              <div><dt>Organizations</dt><dd>{snapshot.nodes.length}</dd></div>
              <div><dt>Audience links</dt><dd>{snapshot.edges.filter((edge) => !edge.isSeed).length}</dd></div>
              <div><dt>External</dt><dd>{analysis.reachableExternalIds.size}</dd></div>
              <div><dt>Max depth</dt><dd>{analysis.maximumDepth}</dd></div>
            </dl>

            <div className="admin-links">
              <button onClick={() => void copyUrl(window.location.origin)}>Copy public URL</button>
              <button onClick={() => void copyUrl(contributionUrl)}>Copy contribution URL</button>
            </div>

            <div className="admin-danger-zone">
              <button className="button danger" disabled={busy !== null} onClick={() => setConfirmReset(true)}>Reset round</button>
              {confirmReset && (
                <motion.div
                  className="confirm-box"
                  role="alertdialog"
                  aria-labelledby="reset-title"
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: "auto" }}
                >
                  <strong id="reset-title">Start a new round?</strong>
                  <p>Seed data stays. Current audience dependencies leave the public graph.</p>
                  <div className="button-row">
                    <button className="button danger" disabled={busy !== null} onClick={() => void run({ type: "reset" })}>Confirm reset</button>
                    <button className="button ghost" onClick={() => setConfirmReset(false)}>Cancel</button>
                  </div>
                </motion.div>
              )}
            </div>

            <div className="moderation-heading">
              <div>
                <span className="eyebrow">Current round</span>
                <h3>Audience dependencies</h3>
              </div>
              <span>{dependencies.length}</span>
            </div>

            <div className="moderation-list">
              {dependencies.length === 0 && <p className="empty-copy">No audience submissions yet.</p>}
              {dependencies.map(({ edge, source, target }) => (
                <article className={`moderation-item ${edge.status}`} key={edge.id}>
                  <p><strong>{source.name}</strong><span>depends on</span><strong>{target.name}</strong></p>
                  <div>
                    <span className={`jurisdiction-dot ${target.jurisdiction}`} />
                    <small>{target.jurisdiction.replaceAll("_", " ")}</small>
                    <button disabled={busy !== null} onClick={() => void changeStatus(edge.id, edge.status === "active" ? "hidden" : "active")}>
                      {edge.status === "active" ? "Hide" : "Restore"}
                    </button>
                  </div>
                </article>
              ))}
            </div>

            <button
              className="logout-button"
              onClick={async () => {
                await logoutAdmin();
                onLogout();
              }}
            >
              Sign out
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.aside>
  );
}
