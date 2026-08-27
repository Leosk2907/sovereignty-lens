"use client";

import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Brand } from "@/components/brand";
import { ApiClientError, getGraphSnapshot, submitCompanyContribution } from "@/lib/api-client";
import {
  CONTRACT_VERSION,
  MAX_CUSTOMERS,
  MAX_DEPENDENCIES,
  companyContributionRequestSchema,
  type GraphNode,
  type Jurisdiction,
  type OrganizationType,
} from "@/lib/contracts";

const CLIENT_ID_KEY = "sovereignty-lens.client-id.v1";
const organizationTypes: Array<Exclude<OrganizationType, "government">> = [
  "cloud", "software", "hardware", "telecom", "consulting", "logistics", "finance", "other",
];
const organizationTypeLabel: Record<Exclude<OrganizationType, "government">, string> = {
  cloud: "Cloud",
  software: "Software",
  hardware: "Hardware",
  telecom: "Telecom",
  consulting: "Consulting",
  logistics: "Logistics",
  finance: "Finance",
  other: "Other",
};

const suggestedDependencies: Array<{
  name: string;
  organizationType: Exclude<OrganizationType, "government">;
  jurisdiction: Jurisdiction;
}> = [
  { name: "Amazon Web Services", organizationType: "cloud", jurisdiction: "united_states" },
  { name: "Microsoft Azure", organizationType: "cloud", jurisdiction: "united_states" },
  { name: "Google Cloud", organizationType: "cloud", jurisdiction: "united_states" },
  { name: "Oracle", organizationType: "software", jurisdiction: "united_states" },
  { name: "Salesforce", organizationType: "software", jurisdiction: "united_states" },
];

const errorCopy: Record<string, string> = {
  VALIDATION_ERROR: "Check your company, customer, and dependency names—none can repeat each other.",
  SOURCE_NOT_FOUND: "One of your customers is no longer active. Reload and choose again.",
  ALREADY_CONTRIBUTED: "This device has already contributed a company profile this round.",
  SESSION_PAUSED: "The presenter has temporarily paused submissions.",
  ROUND_CAPACITY_REACHED: "This round is full. Watch the main graph for the result.",
};

interface DependencyRow {
  key: number;
  name: string;
  organizationType: Exclude<OrganizationType, "government">;
  jurisdiction: Jurisdiction;
}

function newDependencyRow(key: number): DependencyRow {
  return { key, name: "", organizationType: "software", jurisdiction: "europe" };
}

function getClientId() {
  const existing = window.localStorage.getItem(CLIENT_ID_KEY);
  if (existing) return existing;
  const id = crypto.randomUUID();
  window.localStorage.setItem(CLIENT_ID_KEY, id);
  return id;
}

export function CompanyContributionForm() {
  const [nodes, setNodes] = useState<GraphNode[]>([]);
  const [sessionStatus, setSessionStatus] = useState<"open" | "paused">("open");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const errorRef = useRef<HTMLDivElement | null>(null);
  const [success, setSuccess] = useState(false);
  const [companyName, setCompanyName] = useState("");
  const [companyType, setCompanyType] = useState<Exclude<OrganizationType, "government">>("software");
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerIds, setCustomerIds] = useState<string[]>([]);
  const [dependencies, setDependencies] = useState<DependencyRow[]>(() => [newDependencyRow(0)]);
  const nextRowKeyRef = useRef(1);

  useEffect(() => {
    getGraphSnapshot()
      .then((snapshot) => {
        setNodes(snapshot.nodes);
        setSessionStatus(snapshot.session.status);
      })
      .catch((caught) => setError(caught instanceof Error ? caught.message : "Could not load the graph."))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (error) errorRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [error]);

  const europeanNodes = useMemo(() => nodes.filter((node) => node.jurisdiction === "europe"), [nodes]);
  const filteredCustomers = useMemo(() => {
    const normalized = customerQuery.trim().toLowerCase();
    return europeanNodes
      .filter((node) => !normalized || node.name.toLowerCase().includes(normalized))
      .slice(0, 12);
  }, [europeanNodes, customerQuery]);
  const selectedCustomers = useMemo(
    () => customerIds.flatMap((id) => nodes.find((node) => node.id === id) ?? []),
    [customerIds, nodes],
  );

  function toggleCustomer(id: string) {
    setCustomerIds((current) => {
      if (current.includes(id)) return current.filter((existing) => existing !== id);
      if (current.length >= MAX_CUSTOMERS) return current;
      return [...current, id];
    });
  }

  function updateDependency(key: number, update: Partial<DependencyRow>) {
    setDependencies((rows) => rows.map((row) => row.key === key ? { ...row, ...update } : row));
  }

  function addDependency() {
    setDependencies((rows) => {
      if (rows.length >= MAX_DEPENDENCIES) return rows;
      const key = nextRowKeyRef.current;
      nextRowKeyRef.current += 1;
      return [...rows, newDependencyRow(key)];
    });
  }

  function removeDependency(key: number) {
    setDependencies((rows) => rows.length <= 1 ? rows : rows.filter((row) => row.key !== key));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const trimmedCompanyName = companyName.trim();
    const names = [
      trimmedCompanyName.toLowerCase(),
      ...selectedCustomers.map((node) => node.name.trim().toLowerCase()),
      ...dependencies.map((row) => row.name.trim().toLowerCase()),
    ];
    if (new Set(names).size !== names.length) {
      setError("Your company, customer, and dependency names must all be different.");
      return;
    }

    const parsed = companyContributionRequestSchema.safeParse({
      contractVersion: CONTRACT_VERSION,
      anonymousClientId: getClientId(),
      company: {
        name: trimmedCompanyName,
        organizationType: companyType,
        jurisdiction: "europe",
      },
      customerOrganizationIds: customerIds,
      dependencies: dependencies.map((row) => ({
        name: row.name.trim(),
        organizationType: row.organizationType,
        jurisdiction: row.jurisdiction,
      })),
    });
    if (!parsed.success) {
      setError("Fill in your company, at least one European customer, and at least one dependency.");
      return;
    }

    setSubmitting(true);
    try {
      await submitCompanyContribution(parsed.data);
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
        <motion.div
          className="success-icon"
          aria-hidden="true"
          initial={{ opacity: 0, scale: 0.72 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ type: "spring", stiffness: 360, damping: 24 }}
        >✓</motion.div>
        <span className="step-label">Company profile live</span>
        <h1>Now watch the main screen.</h1>
        <p><strong>{companyName}</strong> just joined the network. Its customers and dependencies are appearing now.</p>
        <p className="small-disclaimer">Simulated, unverified demo data—not a factual claim.</p>
      </main>
    );
  }

  const disabled = loading || submitting || sessionStatus === "paused";

  return (
    <main className="contribution-shell company-contribution-shell">
      <header className="mobile-header"><Brand /><span>Company profile</span></header>
      <span className="step-label">Live audience contribution</span>
      <h1 className="company-form-title">Add your company.</h1>
      <p className="company-form-lead">Introduce your company, who it serves in Europe, and what it quietly depends on.</p>

      {sessionStatus === "paused" && <div className="form-notice warning" role="status">Submissions are temporarily paused by the presenter.</div>}
      {error && <div ref={errorRef} className="form-notice error" role="alert">{error}</div>}

      <form className="contribution-form company-profile-form" onSubmit={submit}>
        <fieldset disabled={disabled}>
          <legend><span>01</span>Your company</legend>
          <div className="company-basics-grid">
            <label htmlFor="company-name">Your company name
              <input id="company-name" value={companyName} onChange={(event) => setCompanyName(event.target.value)} maxLength={60} placeholder="e.g. Atlas Cloud" required />
            </label>
            <label>Company type
              <select value={companyType} onChange={(event) => setCompanyType(event.target.value as Exclude<OrganizationType, "government">)}>
                {organizationTypes.map((type) => <option value={type} key={type}>{organizationTypeLabel[type]}</option>)}
              </select>
            </label>
          </div>
          <p className="fixed-jurisdiction"><span className="jurisdiction-dot europe" />Registered as European for this demo</p>
        </fieldset>

        <fieldset disabled={disabled}>
          <legend><span>02</span>Your customers</legend>
          <p className="section-hint">Who in the live graph already depends on <strong>{companyName.trim() || "your company"}</strong>? Choose 1–{MAX_CUSTOMERS} European organizations.</p>
          <label htmlFor="customer-search">Search European organizations
            <input id="customer-search" type="search" value={customerQuery} onChange={(event) => setCustomerQuery(event.target.value)} placeholder="Search organizations…" autoComplete="off" />
          </label>
          <div className="source-options customer-options" role="listbox" aria-label="European organizations" aria-multiselectable="true">
            {loading && <span className="empty-copy">Loading organizations…</span>}
            {!loading && filteredCustomers.map((node) => {
              const selected = customerIds.includes(node.id);
              const atCapacity = !selected && customerIds.length >= MAX_CUSTOMERS;
              return (
                <button type="button" role="option" aria-selected={selected} disabled={atCapacity} key={node.id} onClick={() => toggleCustomer(node.id)}>
                  <span className="jurisdiction-dot europe" />
                  <span><strong>{node.name}</strong><small>{node.organizationType}</small></span>
                  <i aria-hidden="true">{selected ? "✓" : "+"}</i>
                </button>
              );
            })}
          </div>
          <div className="selection-summary">
            <span>{customerIds.length} / {MAX_CUSTOMERS} selected</span>
            {selectedCustomers.map((node) => <button type="button" key={node.id} onClick={() => toggleCustomer(node.id)}>{node.name} ×</button>)}
          </div>
        </fieldset>

        <fieldset disabled={disabled}>
          <legend><span>03</span>Your dependencies</legend>
          <p className="section-hint">What does <strong>{companyName.trim() || "your company"}</strong> depend on? Add 1–{MAX_DEPENDENCIES} providers.</p>
          <div className="dependency-rows">
            <AnimatePresence initial={false}>
              {dependencies.map((row, index) => (
                <motion.div
                  className="dependency-row"
                  key={row.key}
                  layout
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, height: 0 }}
                >
                  <div className="dependency-row-heading">
                    <span>Dependency {index + 1}</span>
                    {dependencies.length > 1 && <button type="button" onClick={() => removeDependency(row.key)} aria-label={`Remove dependency ${index + 1}`}>Remove</button>}
                  </div>
                  <label htmlFor={`dependency-name-${row.key}`}>Dependency company name
                    <input id={`dependency-name-${row.key}`} value={row.name} onChange={(event) => updateDependency(row.key, { name: event.target.value })} maxLength={60} placeholder="e.g. Pacific Data Systems" required />
                  </label>
                  <div className="suggestion-chips" aria-label={`Suggested dependencies for row ${index + 1}`}>
                    {suggestedDependencies.map((suggestion) => (
                      <button
                        type="button"
                        key={suggestion.name}
                        aria-pressed={row.name === suggestion.name}
                        onClick={() => updateDependency(row.key, suggestion)}
                      >
                        {suggestion.name}
                      </button>
                    ))}
                  </div>
                  <div className="form-grid">
                    <label>Company type
                      <select value={row.organizationType} onChange={(event) => updateDependency(row.key, { organizationType: event.target.value as Exclude<OrganizationType, "government"> })}>
                        {organizationTypes.map((type) => <option value={type} key={type}>{organizationTypeLabel[type]}</option>)}
                      </select>
                    </label>
                    <label>Jurisdiction
                      <select value={row.jurisdiction} onChange={(event) => updateDependency(row.key, { jurisdiction: event.target.value as Jurisdiction })}>
                        <option value="europe">Europe</option>
                        <option value="united_states">United States</option>
                        <option value="china">China</option>
                        <option value="other_external">Other non-European</option>
                        <option value="unknown">Unknown</option>
                      </select>
                    </label>
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
          </div>
          {dependencies.length < MAX_DEPENDENCIES && <button type="button" className="button secondary add-row-button" onClick={addDependency}>+ Add another dependency</button>}
        </fieldset>

        <button className="button primary submit-button" disabled={disabled}>
          {submitting ? "Adding company…" : "Add company profile to the graph →"}
        </button>
      </form>
      <p className="small-disclaimer">This is simulated, unverified demo data—not a factual claim.</p>
    </main>
  );
}
