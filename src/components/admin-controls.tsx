"use client";

import { useCallback, useEffect, useState } from "react";
import {
  getAdminDependencies,
  logoutAdmin,
  runAdminAction,
  setDependencyStatus,
} from "@/lib/api-client";
import type { AdminAction, AdminDependency, GraphSnapshot } from "@/lib/contracts";

interface AdminControlsProps {
  snapshot: GraphSnapshot;
  contributionUrl: string;
  onRefresh: () => Promise<void>;
  onLogout: () => void;
}

export function AdminControls({ snapshot, contributionUrl, onRefresh, onLogout }: AdminControlsProps) {
  const [dependencies, setDependencies] = useState<AdminDependency[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);

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
    <aside className="admin-panel" aria-label="Presenter controls">
      <div className="admin-panel-heading">
        <div>
          <span className="eyebrow">Presenter controls</span>
          <h2>Round {snapshot.session.currentRound}</h2>
        </div>
        <span className={`status-pill ${snapshot.session.status}`}>{snapshot.session.status}</span>
      </div>

      <div className="admin-action-grid">
        {snapshot.session.status === "open" ? (
          <button className="button warning" disabled={busy !== null} onClick={() => void run({ type: "pause" })}>Pause submissions</button>
        ) : (
          <button className="button primary" disabled={busy !== null} onClick={() => void run({ type: "resume" })}>Resume submissions</button>
        )}
        <button className="button secondary" disabled={busy !== null} onClick={() => void run({ type: "undo" })}>Undo latest</button>
        <button className="button danger" disabled={busy !== null} onClick={() => setConfirmReset(true)}>Reset round</button>
      </div>

      {confirmReset && (
        <div className="confirm-box" role="alertdialog" aria-labelledby="reset-title">
          <strong id="reset-title">Start a new round?</strong>
          <p>Seed data stays. Current audience dependencies leave the public graph.</p>
          <div className="button-row">
            <button className="button danger" disabled={busy !== null} onClick={() => void run({ type: "reset" })}>Confirm reset</button>
            <button className="button ghost" onClick={() => setConfirmReset(false)}>Cancel</button>
          </div>
        </div>
      )}

      <div className="admin-links">
        <button onClick={() => void copyUrl(window.location.origin)}>Copy public URL</button>
        <button onClick={() => void copyUrl(contributionUrl)}>Copy contribution URL</button>
      </div>

      {message && <p className="admin-message" role="status">{message}</p>}

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
    </aside>
  );
}
