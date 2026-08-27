"use client";

import Link from "next/link";
import { type FormEvent, useEffect, useMemo, useState } from "react";
import { Brand } from "@/components/brand";
import { ApiClientError, getGraphSnapshot, submitDependency } from "@/lib/api-client";
import {
  CONTRACT_VERSION,
  contributionRequestSchema,
  type GraphNode,
  type Jurisdiction,
  type OrganizationType,
} from "@/lib/contracts";

const CLIENT_ID_KEY = "sovereignty-lens.client-id.v1";
const organizationTypes: Array<Exclude<OrganizationType, "government">> = [
  "cloud", "software", "hardware", "telecom", "consulting", "logistics", "finance", "other",
];

const errorCopy: Record<string, string> = {
  VALIDATION_ERROR: "Check the relationship and try again.",
  SOURCE_NOT_FOUND: "That organization is no longer active. Choose another one.",
  DUPLICATE_DEPENDENCY: "That dependency is already on the graph.",
  ALREADY_CONTRIBUTED: "This device has already contributed in the current round.",
  SESSION_PAUSED: "The presenter has temporarily paused submissions.",
  ROUND_CAPACITY_REACHED: "This round is full. Watch the main graph for the result.",
};

function getClientId() {
  const existing = window.localStorage.getItem(CLIENT_ID_KEY);
  if (existing) return existing;
  const id = crypto.randomUUID();
  window.localStorage.setItem(CLIENT_ID_KEY, id);
  return id;
}

export function ContributionForm() {
  const [nodes, setNodes] = useState<GraphNode[]>([]);
  const [sessionStatus, setSessionStatus] = useState<"open" | "paused">("open");
  const [sourceId, setSourceId] = useState("");
  const [query, setQuery] = useState("");
  const [name, setName] = useState("");
  const [organizationType, setOrganizationType] = useState<Exclude<OrganizationType, "government">>("software");
  const [jurisdiction, setJurisdiction] = useState<Jurisdiction>("europe");
  const [externalEnabled, setExternalEnabled] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    const requestedVariant = new URLSearchParams(window.location.search).get("variant");
    setExternalEnabled(requestedVariant === "external" || Math.random() < 0.5);
    getGraphSnapshot()
      .then((snapshot) => {
        setNodes(snapshot.nodes);
        setSessionStatus(snapshot.session.status);
      })
      .catch((caught) => setError(caught instanceof Error ? caught.message : "Could not load the graph."))
      .finally(() => setLoading(false));
  }, []);

  const filteredNodes = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return nodes
      .filter((node) => !normalized || node.name.toLowerCase().includes(normalized))
      .slice(0, 12);
  }, [nodes, query]);

  const selectedSource = nodes.find((node) => node.id === sourceId);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const clientId = getClientId();
    const parsed = contributionRequestSchema.safeParse({
      contractVersion: CONTRACT_VERSION,
      anonymousClientId: clientId,
      sourceOrganizationId: sourceId,
      target: { name, organizationType, jurisdiction },
    });
    if (!parsed.success) {
      setError("Choose a source and enter a company name between 2 and 60 characters.");
      return;
    }
    if (selectedSource?.name.trim().toLowerCase() === name.trim().toLowerCase()) {
      setError("An organization cannot depend on itself.");
      return;
    }
    setSubmitting(true);
    try {
      await submitDependency(parsed.data);
      setSuccess(true);
    } catch (caught) {
      if (caught instanceof ApiClientError) setError(errorCopy[caught.code] ?? caught.message);
      else setError("Something went wrong. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  if (success) {
    return (
      <main className="contribution-shell success-shell">
        <Brand />
        <div className="success-icon" aria-hidden="true">✓</div>
        <span className="eyebrow">Dependency added</span>
        <h1>Now watch the main screen.</h1>
        <p>Your contribution is moving through the dependency graph. It may reveal a connection no one could see before.</p>
        <p className="small-disclaimer">Simulated, unverified demo data—not a factual claim.</p>
      </main>
    );
  }

  return (
    <main className="contribution-shell">
      <header className="mobile-header"><Brand /><Link href="/about">About</Link></header>
      <span className="eyebrow">Live audience experiment</span>
      <h1>Add one hidden dependency.</h1>
      <p className="lead">Choose an organization and add one company it depends on.</p>

      {sessionStatus === "paused" && <div className="form-notice warning" role="status">Submissions are temporarily paused by the presenter.</div>}
      {error && <div className="form-notice error" role="alert">{error}</div>}

      <form className="contribution-form" onSubmit={submit}>
        <fieldset disabled={loading || submitting || sessionStatus === "paused"}>
          <legend>1. Choose the organization</legend>
          <label htmlFor="source-search">Search the live graph</label>
          <input id="source-search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search organizations…" autoComplete="off" />
          <div className="source-options" role="listbox" aria-label="Organizations">
            {loading && <span className="empty-copy">Loading organizations…</span>}
            {!loading && filteredNodes.map((node) => (
              <button type="button" role="option" aria-selected={sourceId === node.id} className={sourceId === node.id ? "selected" : ""} key={node.id} onClick={() => setSourceId(node.id)}>
                <span className={`jurisdiction-dot ${node.jurisdiction}`} />
                <span><strong>{node.name}</strong><small>{node.organizationType}</small></span>
              </button>
            ))}
          </div>
        </fieldset>

        <div className="relation-preview">
          <span>{selectedSource?.name ?? "Selected organization"}</span>
          <i>depends on</i>
          <span>{name || "your company"}</span>
        </div>

        <fieldset disabled={loading || submitting || sessionStatus === "paused"}>
          <legend>2. Add its dependency</legend>
          <label htmlFor="company-name">Company name</label>
          <input id="company-name" value={name} onChange={(event) => setName(event.target.value)} maxLength={60} placeholder="e.g. Atlas Cloud" required />

          <div className="form-grid">
            <label>Company type
              <select value={organizationType} onChange={(event) => setOrganizationType(event.target.value as Exclude<OrganizationType, "government">)}>
                {organizationTypes.map((type) => <option value={type} key={type}>{type}</option>)}
              </select>
            </label>
            <label>Jurisdiction
              <select value={jurisdiction} onChange={(event) => setJurisdiction(event.target.value as Jurisdiction)}>
                <option value="europe">Europe</option>
                {externalEnabled && <>
                  <option value="united_states">United States</option>
                  <option value="china">China</option>
                  <option value="other_external">Other non-European</option>
                  <option value="unknown">Unknown</option>
                </>}
              </select>
            </label>
          </div>
        </fieldset>

        <button className="button primary submit-button" disabled={loading || submitting || sessionStatus === "paused"}>
          {submitting ? "Adding dependency…" : "Add to the live graph →"}
        </button>
      </form>
      <p className="small-disclaimer">This is simulated, unverified demo data—not a factual claim.</p>
    </main>
  );
}
