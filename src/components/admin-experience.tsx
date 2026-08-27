"use client";

import { type FormEvent, useEffect, useState } from "react";
import { Brand } from "@/components/brand";
import { LiveGraphExperience } from "@/components/live-graph-experience";
import { ApiClientError, getAdminSession, isMockMode, loginAdmin } from "@/lib/api-client";

export function AdminExperience() {
  const [checking, setChecking] = useState(true);
  const [authenticated, setAuthenticated] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    getAdminSession()
      .then(() => setAuthenticated(true))
      .catch(() => setAuthenticated(false))
      .finally(() => setChecking(false));
  }, []);

  async function login(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await loginAdmin(password);
      setAuthenticated(true);
    } catch (caught) {
      setError(caught instanceof ApiClientError && caught.code === "UNAUTHORIZED" ? "Incorrect presenter password." : "Could not sign in.");
    } finally {
      setSubmitting(false);
    }
  }

  if (checking) return <main className="center-state"><div className="spinner" /><p>Checking presenter session…</p></main>;
  if (authenticated) return <LiveGraphExperience mode="admin" onAdminLogout={() => setAuthenticated(false)} />;

  return (
    <main className="login-shell">
      <div className="login-backdrop" aria-hidden="true" />
      <section className="login-card">
        <Brand />
        <span className="eyebrow">Presenter access</span>
        <h1>Control the live reveal.</h1>
        <p>Sign in to moderate audience submissions and manage the demo round.</p>
        <form onSubmit={login}>
          <label htmlFor="password">Presenter password</label>
          <input id="password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required autoFocus />
          {error && <div className="form-notice error" role="alert">{error}</div>}
          {isMockMode && <small>Local demo password: <code>demo</code></small>}
          <button className="button primary" disabled={submitting}>{submitting ? "Signing in…" : "Open presenter view →"}</button>
        </form>
      </section>
    </main>
  );
}
