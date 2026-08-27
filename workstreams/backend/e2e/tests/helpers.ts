import { request as playwrightRequest, expect } from '@playwright/test';
import type { APIRequestContext, APIResponse } from '@playwright/test';

/* ------------------------------------------------------------------------- *
 * Contract constants
 * ------------------------------------------------------------------------- */

export const CONTRACT_VERSION = 1 as const;
export const SESSION_SLUG = 'demo';

export const BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080';

/**
 * Fixed identifiers from
 * `src/main/resources/db/migration/V3__seed_demo_session.sql`.
 * The migration pins them as literals precisely so fixtures and tests can rely
 * on them without querying first.
 */
export const SEED = {
  sessionId: '00000000-0000-4000-8000-000000000001',

  /** Root government node the presentation traverses from. */
  root: '00000000-0000-4000-8000-000000000101',
  alpineCivicSystems: '00000000-0000-4000-8000-000000000102',
  balticDataWorks: '00000000-0000-4000-8000-000000000103',
  rhinePublicNetworks: '00000000-0000-4000-8000-000000000104',

  /** root -> Alpine, root -> Rhine, Alpine -> Baltic. */
  edgeRootToAlpine: '00000000-0000-4000-8000-000000000201',
  edgeRootToRhine: '00000000-0000-4000-8000-000000000202',
  edgeAlpineToBaltic: '00000000-0000-4000-8000-000000000203',
} as const;

export const SEED_ORGANIZATION_IDS = [
  SEED.root,
  SEED.alpineCivicSystems,
  SEED.balticDataWorks,
  SEED.rhinePublicNetworks,
] as const;

export const SEED_EDGE_IDS = [
  SEED.edgeRootToAlpine,
  SEED.edgeRootToRhine,
  SEED.edgeAlpineToBaltic,
] as const;

export const SEED_ORGANIZATION_NAMES: Record<string, string> = {
  [SEED.root]: 'European Digital Services Agency',
  [SEED.alpineCivicSystems]: 'Alpine Civic Systems',
  [SEED.balticDataWorks]: 'Baltic Data Works',
  [SEED.rhinePublicNetworks]: 'Rhine Public Networks',
};

/** A syntactically valid UUID that is deliberately absent from the database. */
export const ABSENT_ORGANIZATION_ID = '11111111-1111-4111-8111-111111111111';

/* ------------------------------------------------------------------------- *
 * Contract types (mirrors of contracts/data-contract.md, version 1)
 * ------------------------------------------------------------------------- */

export type Jurisdiction =
  | 'europe'
  | 'united_states'
  | 'china'
  | 'other_external'
  | 'unknown';

export type OrganizationType =
  | 'government'
  | 'cloud'
  | 'software'
  | 'hardware'
  | 'telecom'
  | 'consulting'
  | 'logistics'
  | 'finance'
  | 'other';

export type SessionStatus = 'open' | 'paused';
export type DependencyStatus = 'active' | 'hidden';
export type AdminActionType = 'pause' | 'resume' | 'reset' | 'undo';
export type AdminInvalidationReason =
  | 'pause'
  | 'resume'
  | 'hide'
  | 'restore'
  | 'undo'
  | 'reset';

export interface SessionSummary {
  id: string;
  slug: string;
  title: string;
  status: SessionStatus;
  currentRound: number;
  rootOrganizationId: string;
}

export interface GraphNode {
  id: string;
  name: string;
  organizationType: OrganizationType;
  jurisdiction: Jurisdiction;
  isSeed: boolean;
}

export interface GraphEdge {
  id: string;
  sourceOrganizationId: string;
  targetOrganizationId: string;
  isSeed: boolean;
  status: DependencyStatus;
  createdAt: string;
}

export interface GraphSnapshot {
  contractVersion: 1;
  session: SessionSummary;
  nodes: GraphNode[];
  edges: GraphEdge[];
  serverTime: string;
}

export interface ContributionResult {
  contractVersion: 1;
  eventId: string;
  round: number;
  node: GraphNode;
  edge: GraphEdge;
}

export interface AdminActionResult {
  contractVersion: 1;
  eventId: string;
  session: SessionSummary;
}

export interface DependencyStatusResult {
  contractVersion: 1;
  eventId: string;
  edge: GraphEdge;
}

export interface AdminDependency {
  edge: GraphEdge;
  source: GraphNode;
  target: GraphNode;
}

export interface AdminDependencyList {
  contractVersion: 1;
  session: SessionSummary;
  dependencies: AdminDependency[];
}

export interface ApiErrorResponse {
  contractVersion: 1;
  error: {
    code: string;
    message: string;
    retryable: boolean;
    field?: string;
  };
}

/* ------------------------------------------------------------------------- *
 * URLs
 * ------------------------------------------------------------------------- */

export function apiUrl(path: string): string {
  return `${BASE_URL.replace(/\/+$/, '')}${path}`;
}

export const paths = {
  health: '/api/health',
  graph: (slug = SESSION_SLUG) => `/api/sessions/${slug}/graph`,
  dependencies: (slug = SESSION_SLUG) => `/api/sessions/${slug}/dependencies`,
  events: (slug = SESSION_SLUG) => `/api/sessions/${slug}/events`,
  adminLogin: '/api/admin/login',
  adminLogout: '/api/admin/logout',
  adminActions: (slug = SESSION_SLUG) => `/api/admin/sessions/${slug}/actions`,
  adminDependencies: (slug = SESSION_SLUG) =>
    `/api/admin/sessions/${slug}/dependencies`,
  adminDependency: (id: string) => `/api/admin/dependencies/${id}`,
};

/* ------------------------------------------------------------------------- *
 * Identity
 * ------------------------------------------------------------------------- */

/** A fresh anonymous client id, i.e. one more simulated phone. */
export function newClientId(): string {
  return crypto.randomUUID();
}

/** A company name that cannot collide with another test run. */
export function uniqueCompanyName(prefix = 'Testco'): string {
  return `${prefix} ${crypto.randomUUID().slice(0, 8)}`;
}

/* ------------------------------------------------------------------------- *
 * Admin authentication
 * ------------------------------------------------------------------------- */

/**
 * The presenter password. Never hardcoded: it is deployment configuration and
 * lives in `.env` as `APP_ADMIN_PASSWORD`.
 */
export function adminPassword(): string {
  const password = process.env.ADMIN_PASSWORD ?? process.env.APP_ADMIN_PASSWORD;
  if (!password) {
    throw new Error(
      'ADMIN_PASSWORD is not set. The admin end-to-end tests need the presenter ' +
        'password of the running stack. Set it to the same value as ' +
        'APP_ADMIN_PASSWORD in workstreams/backend/.env, for example:\n' +
        '  PowerShell: $env:ADMIN_PASSWORD = "<the value from .env>"\n' +
        '  bash:       export ADMIN_PASSWORD="<the value from .env>"\n' +
        'Never commit the value and never hardcode it in a test.',
    );
  }
  return password;
}

/**
 * Logs in as the presenter and returns the `sl_admin` cookie as a ready-to-use
 * `Cookie` header value.
 *
 * The cookie is returned rather than mutating the caller's context implicitly,
 * so a spec can hold an authenticated and an unauthenticated identity at the
 * same time — the 401 tests depend on that.
 */
export async function adminLogin(request: APIRequestContext): Promise<string> {
  const response = await request.post(paths.adminLogin, {
    data: { contractVersion: CONTRACT_VERSION, password: adminPassword() },
  });

  expect(
    response.status(),
    'Admin login failed. Does ADMIN_PASSWORD match APP_ADMIN_PASSWORD of the running stack?',
  ).toBe(200);

  const cookie = extractAdminCookie(response);
  expect(cookie, 'POST /api/admin/login did not set the sl_admin cookie').not.toBeNull();
  return cookie!;
}

function extractAdminCookie(response: APIResponse): string | null {
  const raw = response.headersArray().filter((h) => h.name.toLowerCase() === 'set-cookie');
  for (const header of raw) {
    // A single Set-Cookie header may be reported with embedded newlines.
    for (const line of header.value.split('\n')) {
      const match = /(^|;\s*)sl_admin=([^;]*)/.exec(line);
      if (match && match[2] !== '') {
        return `sl_admin=${match[2]}`;
      }
    }
  }
  return null;
}

/** Request options carrying the presenter cookie. */
export function withAdmin(cookie: string): { headers: Record<string, string> } {
  return { headers: { Cookie: cookie } };
}

/**
 * A brand-new API context with an empty cookie jar, for asserting that an
 * endpoint really does reject an unauthenticated caller.
 */
export async function newAnonymousContext(): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: BASE_URL,
    extraHTTPHeaders: { Accept: 'application/json' },
  });
}

/* ------------------------------------------------------------------------- *
 * Admin actions
 * ------------------------------------------------------------------------- */

export async function adminAction(
  request: APIRequestContext,
  type: AdminActionType,
  cookie: string,
): Promise<APIResponse> {
  return request.post(paths.adminActions(), {
    ...withAdmin(cookie),
    data: { contractVersion: CONTRACT_VERSION, action: { type } },
  });
}

/**
 * Starts a fresh round so a test never inherits contributions, contributor
 * fingerprints, or paused state from an earlier test or an earlier run.
 *
 * Reset never deletes data — it increments `currentRound` and reopens the
 * session, which is exactly the isolation this suite needs.
 */
export async function resetRound(
  request: APIRequestContext,
  cookie?: string,
): Promise<AdminActionResult> {
  const authCookie = cookie ?? (await adminLogin(request));
  const response = await adminAction(request, 'reset', authCookie);
  expect(response.status(), 'admin reset failed').toBe(200);
  return (await response.json()) as AdminActionResult;
}

export async function setDependencyStatus(
  request: APIRequestContext,
  cookie: string,
  dependencyId: string,
  status: DependencyStatus,
): Promise<APIResponse> {
  return request.patch(paths.adminDependency(dependencyId), {
    ...withAdmin(cookie),
    data: { contractVersion: CONTRACT_VERSION, status },
  });
}

export async function listAdminDependencies(
  request: APIRequestContext,
  cookie: string,
): Promise<AdminDependencyList> {
  const response = await request.get(paths.adminDependencies(), withAdmin(cookie));
  expect(response.status()).toBe(200);
  return (await response.json()) as AdminDependencyList;
}

/* ------------------------------------------------------------------------- *
 * Public reads and contributions
 * ------------------------------------------------------------------------- */

export async function getSnapshot(
  request: APIRequestContext,
  slug = SESSION_SLUG,
): Promise<GraphSnapshot> {
  const response = await request.get(paths.graph(slug));
  expect(response.status()).toBe(200);
  return (await response.json()) as GraphSnapshot;
}

export interface ContributionInput {
  clientId: string;
  sourceId: string;
  name: string;
  type?: string;
  jurisdiction?: string;
  /** Override the wire contract version, for negative tests. */
  contractVersion?: number;
  slug?: string;
}

/** Posts one well-formed `ContributionRequest`. */
export async function contribute(
  request: APIRequestContext,
  input: ContributionInput,
): Promise<APIResponse> {
  const {
    clientId,
    sourceId,
    name,
    type = 'cloud',
    jurisdiction = 'europe',
    contractVersion = CONTRACT_VERSION,
    slug = SESSION_SLUG,
  } = input;

  return request.post(paths.dependencies(slug), {
    data: {
      contractVersion,
      anonymousClientId: clientId,
      sourceOrganizationId: sourceId,
      target: { name, organizationType: type, jurisdiction },
    },
  });
}

/** Contributes and asserts the 201, returning the canonical result. */
export async function contributeOk(
  request: APIRequestContext,
  input: ContributionInput,
): Promise<ContributionResult> {
  const response = await contribute(request, input);
  expect(response.status(), await safeBody(response)).toBe(201);
  return (await response.json()) as ContributionResult;
}

/** Asserts the uniform error envelope and returns it. */
export async function expectApiError(
  response: APIResponse,
  status: number,
  code: string,
): Promise<ApiErrorResponse> {
  expect(response.status(), await safeBody(response)).toBe(status);
  const body = (await response.json()) as ApiErrorResponse;
  expect(body.contractVersion).toBe(CONTRACT_VERSION);
  // Assert on the machine-readable code only; `message` is human copy and free
  // to change without a contract bump.
  expect(body.error.code).toBe(code);
  expect(typeof body.error.message).toBe('string');
  expect(typeof body.error.retryable).toBe('boolean');
  // Only transient INTERNAL_ERROR is retryable.
  expect(body.error.retryable).toBe(code === 'INTERNAL_ERROR');
  return body;
}

async function safeBody(response: APIResponse): Promise<string> {
  try {
    return `unexpected status ${response.status()}: ${await response.text()}`;
  } catch {
    return `unexpected status ${response.status()}`;
  }
}

/* ------------------------------------------------------------------------- *
 * Server-Sent Events
 * ------------------------------------------------------------------------- */

export interface SseEvent {
  /** The SSE `id:` field. Equals the event's `eventId`. */
  id?: string;
  /** The SSE `event:` field, e.g. `dependency.created`. */
  event?: string;
  /** The joined `data:` payload, verbatim. */
  data: string;
  /** `data` parsed as JSON, or undefined when it is not valid JSON. */
  json?: any;
}

/**
 * Incremental Server-Sent Events parser.
 *
 * Implements the framing described in contracts/transport-amendment.md and the
 * WHATWG event-stream rules the backend follows:
 *
 * - records are terminated by a blank line
 * - a line beginning with `:` is a comment (the `: ping` heartbeat) and is
 *   ignored entirely
 * - `field: value` — exactly one optional leading space of the value is
 *   stripped
 * - repeated `data:` lines are joined with `\n`
 * - a record with no data is not dispatched
 *
 * Deliberately incremental rather than a regex over an accumulated buffer: a
 * chunk boundary can fall anywhere, including inside a field name.
 */
export class SseParser {
  private buffer = '';
  private id?: string;
  private event?: string;
  private data: string[] = [];

  push(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const events: SseEvent[] = [];

    // Normalize CRLF / CR terminators to LF, then consume whole lines only.
    this.buffer = this.buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');

    let newlineAt: number;
    while ((newlineAt = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, newlineAt);
      this.buffer = this.buffer.slice(newlineAt + 1);
      const dispatched = this.consumeLine(line);
      if (dispatched) events.push(dispatched);
    }

    return events;
  }

  private consumeLine(line: string): SseEvent | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null; // heartbeat / comment

    const colonAt = line.indexOf(':');
    const field = colonAt === -1 ? line : line.slice(0, colonAt);
    let value = colonAt === -1 ? '' : line.slice(colonAt + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'id':
        this.id = value;
        break;
      case 'event':
        this.event = value;
        break;
      case 'data':
        this.data.push(value);
        break;
      default:
        // `retry:` and unknown fields are ignored.
        break;
    }
    return null;
  }

  private dispatch(): SseEvent | null {
    if (this.data.length === 0) {
      // A blank line after only comments or an `id:` carries no event.
      this.event = undefined;
      return null;
    }

    const data = this.data.join('\n');
    const result: SseEvent = { id: this.id, event: this.event, data };
    try {
      result.json = JSON.parse(data);
    } catch {
      /* left undefined; the test asserts on it */
    }

    this.id = undefined;
    this.event = undefined;
    this.data = [];
    return result;
  }
}

export interface ReadSseOptions {
  /** Resolve as soon as this many events have arrived. Default 1. */
  count?: number;
  /** Give up and resolve with whatever arrived. Default 15000. */
  timeoutMs?: number;
  /**
   * Invoked once the stream is open, so a test can trigger the very event it
   * is waiting for without racing the connection.
   */
  onOpen?: () => Promise<void> | void;
  /**
   * Grace period after the response headers arrive, before `onOpen` runs. Gives
   * the server a moment to register the emitter with its event publisher.
   */
  openDelayMs?: number;
  /** Optional `Last-Event-ID` for resume behaviour. */
  lastEventId?: string;
}

/**
 * Consumes a Server-Sent Events stream and resolves once `count` events have
 * arrived or the timeout elapses.
 *
 * Playwright's `APIRequestContext` buffers the whole response body before
 * handing it back, which never completes for a long-lived stream, so this uses
 * Node's `fetch` and iterates `response.body` instead.
 */
export async function readSseEvents(
  url: string,
  options: ReadSseOptions = {},
): Promise<SseEvent[]> {
  const {
    count = 1,
    timeoutMs = 15_000,
    onOpen,
    openDelayMs = 300,
    lastEventId,
  } = options;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const collected: SseEvent[] = [];

  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  if (lastEventId) headers['Last-Event-ID'] = lastEventId;

  try {
    const response = await fetch(url, { headers, signal: controller.signal });

    if (!response.ok) {
      throw new Error(
        `SSE stream ${url} responded ${response.status}: ${await response.text()}`,
      );
    }
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) {
      throw new Error(`SSE stream ${url} returned content-type "${contentType}"`);
    }
    if (!response.body) {
      throw new Error(`SSE stream ${url} returned no body`);
    }

    // Trigger the action that produces the events only once we are listening.
    const trigger = (async () => {
      if (!onOpen) return;
      if (openDelayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, openDelayMs));
      }
      await onOpen();
    })();
    // Never let a failing trigger surface as an unhandled rejection; it is
    // re-thrown below once reading has finished.
    let triggerError: unknown;
    trigger.catch((error) => {
      triggerError = error;
      controller.abort();
    });

    const parser = new SseParser();
    const decoder = new TextDecoder();

    try {
      for await (const chunk of response.body as AsyncIterable<Uint8Array>) {
        for (const event of parser.push(decoder.decode(chunk, { stream: true }))) {
          collected.push(event);
        }
        if (collected.length >= count) break;
      }
    } catch (error) {
      // An abort is the normal way this function stops reading.
      if (!isAbortError(error)) throw error;
    }

    if (triggerError) throw triggerError;
    await trigger.catch(() => undefined);

    return collected;
  } catch (error) {
    if (isAbortError(error)) return collected;
    throw error;
  } finally {
    clearTimeout(timer);
    controller.abort();
  }
}

function isAbortError(error: unknown): boolean {
  return (
    error instanceof Error &&
    (error.name === 'AbortError' || /aborted/i.test(error.message))
  );
}

/** Convenience: wait for the first event with the given SSE `event:` name. */
export function findEvent(events: SseEvent[], name: string): SseEvent | undefined {
  return events.find((event) => event.event === name);
}
