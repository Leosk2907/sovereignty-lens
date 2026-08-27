"use client";

import {
  CONTRACT_VERSION,
  type AdminAction,
  type AdminActionResult,
  type AdminDependencyList,
  type AdminInvalidationReason,
  type AdminLoginResult,
  type AdminLogoutResult,
  type AdminSessionResult,
  type CompanyContributionRequest,
  type CompanyContributionConnection,
  type CompanyContributionResult,
  type DependencyCreatedEvent,
  type DependencyStatus,
  type DependencyStatusResult,
  type GraphEdge,
  type GraphEvent,
  type GraphInvalidatedEvent,
  type GraphNode,
  type GraphSnapshot,
  type SessionSummary,
} from "@/lib/contracts";
import { populatedDemoGraphFixture } from "@/lib/fixtures";

const STATE_KEY = "sovereignty-lens.mock-state.v2";
const AUTH_KEY = "sovereignty-lens.mock-admin.v1";
const CHANNEL_NAME = "sovereignty-lens.mock-events.v1";

interface StoredDependency {
  edge: GraphEdge;
  round: number | null;
  contributorId: string | null;
}

interface MockState {
  session: SessionSummary;
  nodes: GraphNode[];
  dependencies: StoredDependency[];
}

type EventListener = (event: GraphEvent) => void;
const listeners = new Set<EventListener>();

function freshState(): MockState {
  return {
    session: structuredClone(populatedDemoGraphFixture.session),
    nodes: structuredClone(populatedDemoGraphFixture.nodes),
    dependencies: populatedDemoGraphFixture.edges.map((edge, index) => ({
      edge: structuredClone(edge),
      round: edge.isSeed ? null : populatedDemoGraphFixture.session.currentRound,
      contributorId: edge.isSeed ? null : `dummy-contributor-${index}`,
    })),
  };
}

function loadState(): MockState {
  if (typeof window === "undefined") return freshState();
  const stored = window.localStorage.getItem(STATE_KEY);
  if (!stored) {
    const initial = freshState();
    saveState(initial);
    return initial;
  }
  try {
    return JSON.parse(stored) as MockState;
  } catch {
    const initial = freshState();
    saveState(initial);
    return initial;
  }
}

function saveState(state: MockState) {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STATE_KEY, JSON.stringify(state));
  }
}

function emit(event: GraphEvent) {
  listeners.forEach((listener) => listener(event));
  if (typeof BroadcastChannel !== "undefined") {
    const channel = new BroadcastChannel(CHANNEL_NAME);
    channel.postMessage(event);
    channel.close();
  }
}

function publicSnapshot(state: MockState): GraphSnapshot {
  const visibleDependencies = state.dependencies.filter(
    ({ edge, round }) =>
      edge.status === "active" && (edge.isSeed || round === state.session.currentRound),
  );
  const nodeIds = new Set<string>([state.session.rootOrganizationId]);
  visibleDependencies.forEach(({ edge }) => {
    nodeIds.add(edge.sourceOrganizationId);
    nodeIds.add(edge.targetOrganizationId);
  });
  return {
    contractVersion: CONTRACT_VERSION,
    session: structuredClone(state.session),
    nodes: state.nodes.filter((node) => nodeIds.has(node.id)).map((node) => structuredClone(node)),
    edges: visibleDependencies.map(({ edge }) => structuredClone(edge)),
    serverTime: new Date().toISOString(),
  };
}

function requireAdmin() {
  if (typeof window === "undefined" || window.sessionStorage.getItem(AUTH_KEY) !== "true") {
    throw new Error("UNAUTHORIZED");
  }
}

function invalidation(state: MockState, reason: AdminInvalidationReason): GraphInvalidatedEvent {
  return {
    contractVersion: CONTRACT_VERSION,
    event: "graph.invalidated",
    eventId: crypto.randomUUID(),
    sessionSlug: state.session.slug,
    round: state.session.currentRound,
    reason,
    occurredAt: new Date().toISOString(),
  };
}

export async function mockGetGraph(): Promise<GraphSnapshot> {
  return publicSnapshot(loadState());
}

function normalizeName(name: string): string {
  return name.normalize("NFKC").trim().replace(/\s+/g, " ").toLowerCase();
}

export async function mockSubmitCompanyContribution(
  request: CompanyContributionRequest,
): Promise<CompanyContributionResult> {
  const state = loadState();
  if (state.session.status === "paused") throw new Error("SESSION_PAUSED");
  if (state.dependencies.some(
    ({ round, contributorId }) => round === state.session.currentRound && contributorId === request.anonymousClientId,
  )) {
    throw new Error("ALREADY_CONTRIBUTED");
  }

  const publicGraph = publicSnapshot(state);
  const publicNodeById = new Map(publicGraph.nodes.map((node) => [node.id, node]));
  const newEdgeCount = request.customerOrganizationIds.length + request.dependencies.length;
  const currentRoundCount = state.dependencies.filter(({ round }) => round === state.session.currentRound).length;
  if (currentRoundCount + newEdgeCount > 150) throw new Error("ROUND_CAPACITY_REACHED");

  const companyNormalized = normalizeName(request.company.name);
  if (state.nodes.some((node) => normalizeName(node.name) === companyNormalized)) {
    throw new Error("VALIDATION_ERROR");
  }

  const customerIds = new Set(request.customerOrganizationIds);
  if (customerIds.size !== request.customerOrganizationIds.length) throw new Error("VALIDATION_ERROR");
  const customers = request.customerOrganizationIds.map((id) => {
    const node = publicNodeById.get(id);
    if (!node) throw new Error("SOURCE_NOT_FOUND");
    if (node.jurisdiction !== "europe") throw new Error("VALIDATION_ERROR");
    return node;
  });
  const customerNames = new Set(customers.map((node) => normalizeName(node.name)));
  const dependencyNames = new Set<string>();
  for (const dependency of request.dependencies) {
    const normalized = normalizeName(dependency.name);
    if (normalized === companyNormalized || customerNames.has(normalized) || dependencyNames.has(normalized)) {
      throw new Error("VALIDATION_ERROR");
    }
    dependencyNames.add(normalized);
  }

  const now = new Date().toISOString();
  const company: GraphNode = {
    id: crypto.randomUUID(),
    name: request.company.name.trim().replace(/\s+/g, " "),
    organizationType: request.company.organizationType,
    jurisdiction: "europe",
    isSeed: false,
  };
  state.nodes.push(company);

  const events: DependencyCreatedEvent[] = [];
  const customerConnections: CompanyContributionConnection[] = [];
  for (const customer of customers) {
    const edge: GraphEdge = {
      id: crypto.randomUUID(),
      sourceOrganizationId: customer.id,
      targetOrganizationId: company.id,
      isSeed: false,
      status: "active",
      createdAt: now,
    };
    state.dependencies.push({ edge, round: state.session.currentRound, contributorId: request.anonymousClientId });
    const event: DependencyCreatedEvent = {
      contractVersion: CONTRACT_VERSION,
      event: "dependency.created",
      eventId: crypto.randomUUID(),
      sessionSlug: state.session.slug,
      round: state.session.currentRound,
      node: company,
      edge,
      occurredAt: now,
    };
    events.push(event);
    customerConnections.push({ eventId: event.eventId, node: company, edge });
  }

  const dependencyConnections: CompanyContributionConnection[] = [];
  for (const dependency of request.dependencies) {
    const normalized = normalizeName(dependency.name);
    let node = state.nodes.find((candidate) => normalizeName(candidate.name) === normalized);
    if (!node) {
      node = {
        id: crypto.randomUUID(),
        name: dependency.name.trim().replace(/\s+/g, " "),
        organizationType: dependency.organizationType,
        jurisdiction: dependency.jurisdiction,
        isSeed: false,
      };
      state.nodes.push(node);
    }
    const edge: GraphEdge = {
      id: crypto.randomUUID(),
      sourceOrganizationId: company.id,
      targetOrganizationId: node.id,
      isSeed: false,
      status: "active",
      createdAt: now,
    };
    state.dependencies.push({ edge, round: state.session.currentRound, contributorId: request.anonymousClientId });
    const event: DependencyCreatedEvent = {
      contractVersion: CONTRACT_VERSION,
      event: "dependency.created",
      eventId: crypto.randomUUID(),
      sessionSlug: state.session.slug,
      round: state.session.currentRound,
      node,
      edge,
      occurredAt: now,
    };
    events.push(event);
    dependencyConnections.push({ eventId: event.eventId, node, edge });
  }

  saveState(state);
  events.forEach((event, index) => {
    setTimeout(() => emit(event), index * 250);
  });

  return {
    contractVersion: CONTRACT_VERSION,
    round: state.session.currentRound,
    company,
    customerConnections,
    dependencyConnections,
  };
}

export function mockSubscribe(listener: EventListener): () => void {
  listeners.add(listener);
  let channel: BroadcastChannel | undefined;
  if (typeof BroadcastChannel !== "undefined") {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = (message) => listener(message.data as GraphEvent);
  }
  return () => {
    listeners.delete(listener);
    channel?.close();
  };
}

export async function mockLogin(password: string): Promise<AdminLoginResult> {
  if (password !== "demo") throw new Error("UNAUTHORIZED");
  window.sessionStorage.setItem(AUTH_KEY, "true");
  return { contractVersion: CONTRACT_VERSION, authenticated: true };
}

export async function mockGetAdminSession(): Promise<AdminSessionResult> {
  requireAdmin();
  return { contractVersion: CONTRACT_VERSION, authenticated: true, session: loadState().session };
}

export async function mockLogout(): Promise<AdminLogoutResult> {
  window.sessionStorage.removeItem(AUTH_KEY);
  return { contractVersion: CONTRACT_VERSION, authenticated: false };
}

export async function mockGetAdminDependencies(): Promise<AdminDependencyList> {
  requireAdmin();
  const state = loadState();
  const nodeById = new Map(state.nodes.map((node) => [node.id, node]));
  return {
    contractVersion: CONTRACT_VERSION,
    session: state.session,
    dependencies: state.dependencies
      .filter(({ edge, round }) => !edge.isSeed && round === state.session.currentRound)
      .sort((a, b) => b.edge.createdAt.localeCompare(a.edge.createdAt))
      .flatMap(({ edge }) => {
        const source = nodeById.get(edge.sourceOrganizationId);
        const target = nodeById.get(edge.targetOrganizationId);
        return source && target ? [{ edge, source, target }] : [];
      }),
  };
}

export async function mockAdminAction(action: AdminAction): Promise<AdminActionResult> {
  requireAdmin();
  const state = loadState();
  const reason: AdminInvalidationReason = action.type;
  if (action.type === "pause") state.session.status = "paused";
  if (action.type === "resume") state.session.status = "open";
  if (action.type === "reset") {
    state.session.currentRound += 1;
    state.session.status = "open";
  }
  if (action.type === "undo") {
    const latest = state.dependencies
      .filter(({ edge, round }) => !edge.isSeed && round === state.session.currentRound && edge.status === "active")
      .sort((a, b) => b.edge.createdAt.localeCompare(a.edge.createdAt))[0];
    if (latest) latest.edge.status = "hidden";
  }
  saveState(state);
  const event = invalidation(state, reason);
  emit(event);
  return { contractVersion: CONTRACT_VERSION, eventId: event.eventId, session: state.session };
}

export async function mockSetDependencyStatus(id: string, status: DependencyStatus): Promise<DependencyStatusResult> {
  requireAdmin();
  const state = loadState();
  const stored = state.dependencies.find(({ edge, round }) => edge.id === id && !edge.isSeed && round === state.session.currentRound);
  if (!stored) throw new Error("NOT_FOUND");
  stored.edge.status = status;
  saveState(state);
  const event = invalidation(state, status === "active" ? "restore" : "hide");
  emit(event);
  return { contractVersion: CONTRACT_VERSION, eventId: event.eventId, edge: stored.edge };
}
