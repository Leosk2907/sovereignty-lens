# Sovereignty Lens data contract

Contract version: `1`

This is the sole source of truth for data exchanged between the database,
backend, audience form, presentation, and admin interface. Implementations must
import the TypeScript types and Zod schemas generated from this contract; they
must not recreate local variants.

Breaking field, enum, meaning, or endpoint changes require a contract-version
increment and coordinated updates to every workstream. Database columns use
`snake_case`; HTTP and Realtime JSON use the `camelCase` fields defined here.

## Scalar rules

- All IDs are lowercase UUID strings.
- All timestamps are UTC RFC 3339 strings, serialized by JavaScript as ISO 8601.
- A round is a positive integer.
- Company names are trimmed strings containing 2-60 Unicode characters.
- JSON request and event schemas are strict: unknown fields are rejected.
- Optional fields are omitted, not serialized as `undefined`.
- Nullable database fields use `null`; public JSON avoids nullable fields unless
  explicitly declared here.
- Every HTTP response and Realtime event includes `contractVersion: 1`.

## Enumerations

```ts
export const CONTRACT_VERSION = 1 as const;

export type Jurisdiction =
  | "europe"
  | "united_states"
  | "china"
  | "other_external"
  | "unknown";

export type OrganizationType =
  | "government"
  | "cloud"
  | "software"
  | "hardware"
  | "telecom"
  | "consulting"
  | "logistics"
  | "finance"
  | "other";

export type SessionStatus = "open" | "paused";
export type DependencyStatus = "active" | "hidden";

export type AdminInvalidationReason =
  | "pause"
  | "resume"
  | "hide"
  | "restore"
  | "undo"
  | "reset";
```

`united_states`, `china`, and `other_external` count as external exposure.
`europe` and `unknown` do not. Unknown means unresolved, not European.

## Core graph entities

```ts
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
```

An edge always means `source organization depends on target organization`.
Changing that direction is a breaking contract change.

Public graph snapshots contain only:

- Seed edges with `status: "active"`
- Active audience edges from `session.currentRound`
- Nodes referenced by those edges, plus the root node

Every edge in a snapshot must reference nodes present in the same snapshot.
Arrays are ordered deterministically: nodes by creation time then ID, edges by
creation time then ID. Clients must still identify records by ID, not position.

## Company-profile contribution API

The public form posts to `POST /api/sessions/demo/company-contributions`. One
submission adds a European company profile, connects one to three existing
European customers to it, and connects it to one to three dependencies. The
entire batch commits or rolls back together.

### Request

```ts
export interface CompanyDependency {
  name: string;
  organizationType: Exclude<OrganizationType, "government">;
  jurisdiction: Jurisdiction;
}

export interface CompanyContributionRequest {
  contractVersion: 1;
  anonymousClientId: string;
  company: {
    name: string;
    organizationType: Exclude<OrganizationType, "government">;
    jurisdiction: "europe";
  };
  customerOrganizationIds: string[]; // 1-3 existing European nodes
  dependencies: CompanyDependency[]; // 1-3 providers
}
```

`anonymousClientId` is a UUID generated and persisted by the browser. The API
hashes it before database storage. It is never returned or broadcast.

For every customer ID, the API creates `customer -> contributed company`; for
every dependency, it creates `contributed company -> dependency`. These both use
the invariant `source depends on target`. Customer IDs must be distinct,
reachable in the current public graph, and European. The contributed company
must be new in the session. All company, customer, and dependency names in the
batch must be distinct after normalization.

### Success response

```ts
export interface CompanyContributionConnection {
  eventId: string;
  node: GraphNode;
  edge: GraphEdge;
}

export interface CompanyContributionResult {
  contractVersion: 1;
  round: number;
  company: GraphNode;
  customerConnections: CompanyContributionConnection[]; // 1-3
  dependencyConnections: CompanyContributionConnection[]; // 1-3
}
```

Each connection is the exact `eventId`, node, and edge sent in one corresponding
`dependency.created` Realtime event. A customer connection carries the
contributed company as `node`; a dependency connection carries the dependency
provider as `node`. All objects are canonical persisted records.

### Errors

```ts
export type ApiErrorCode =
  | "VALIDATION_ERROR"
  | "SESSION_NOT_FOUND"
  | "SOURCE_NOT_FOUND"
  | "DUPLICATE_DEPENDENCY"
  | "ALREADY_CONTRIBUTED"
  | "SESSION_PAUSED"
  | "ROUND_CAPACITY_REACHED"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "INTERNAL_ERROR";

export interface ApiErrorResponse {
  contractVersion: 1;
  error: {
    code: ApiErrorCode;
    message: string;
    retryable: boolean;
    field?: string;
  };
}
```

HTTP status mapping is fixed:

| Status | Codes |
| --- | --- |
| `400` | `VALIDATION_ERROR` |
| `401` | `UNAUTHORIZED` |
| `403` | `FORBIDDEN` |
| `404` | `SESSION_NOT_FOUND`, `SOURCE_NOT_FOUND`, `NOT_FOUND` |
| `409` | `DUPLICATE_DEPENDENCY`, `ALREADY_CONTRIBUTED` |
| `423` | `SESSION_PAUSED` |
| `429` | `ROUND_CAPACITY_REACHED` |
| `500` | `INTERNAL_ERROR` |

Only `INTERNAL_ERROR` caused by a transient dependency and network-level client
errors are retryable. Validation, duplicate, paused, and capacity errors are not.

## Realtime events

The topic is `sovereignty:<sessionSlug>:round:<round>`. The demo topic for round
three is therefore `sovereignty:demo:round:3`.

```ts
export interface DependencyCreatedEvent {
  contractVersion: 1;
  event: "dependency.created";
  eventId: string;
  sessionSlug: string;
  round: number;
  node: GraphNode;
  edge: GraphEdge;
  occurredAt: string;
}

export interface GraphInvalidatedEvent {
  contractVersion: 1;
  event: "graph.invalidated";
  eventId: string;
  sessionSlug: string;
  round: number;
  reason: AdminInvalidationReason;
  occurredAt: string;
}

export type GraphEvent = DependencyCreatedEvent | GraphInvalidatedEvent;
```

One `dependency.created` event is emitted for every edge in a committed company
profile batch. The messages are inserted in the same database transaction as
the company, providers, and edges. Each contains the same `eventId`, canonical
node, and canonical edge as its `CompanyContributionConnection` in the HTTP
success response. Consumers apply events idempotently using `eventId`, `node.id`,
and `edge.id`, then reconcile with `GraphSnapshot`.

`graph.invalidated` carries no graph records. Consumers refetch the authoritative
snapshot immediately. On reset it uses the new current round; the client leaves
the old topic, fetches the snapshot, and subscribes to the new topic.

Consumers reject unsupported versions, malformed data, or events for another
session/round and fetch a snapshot. Realtime never overrides persisted state.

## Admin API

```ts
export interface AdminLoginRequest {
  contractVersion: 1;
  password: string;
}

export interface AdminLoginResult {
  contractVersion: 1;
  authenticated: true;
}

export interface AdminSessionResult {
  contractVersion: 1;
  authenticated: true;
  session: SessionSummary;
}

export interface AdminLogoutResult {
  contractVersion: 1;
  authenticated: false;
}

export type AdminAction =
  | { type: "pause" }
  | { type: "resume" }
  | { type: "reset" }
  | { type: "undo" };

export interface AdminActionRequest {
  contractVersion: 1;
  action: AdminAction;
}

export interface AdminActionResult {
  contractVersion: 1;
  eventId: string;
  session: SessionSummary;
}

export interface DependencyStatusRequest {
  contractVersion: 1;
  status: DependencyStatus;
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
```

The admin dependency list contains all current-round, non-seed dependencies,
including hidden entries, ordered newest first. Public graph snapshots never
include hidden edges or nodes made unreachable solely by hidden edges.

`GET /api/admin/session` returns `AdminSessionResult` or the canonical `401`
error. `POST /api/admin/logout` clears the signed cookie and returns
`AdminLogoutResult`.

## Database-to-JSON mapping

| Database | JSON |
| --- | --- |
| `sessions.current_round` | `session.currentRound` |
| `sessions.root_organization_id` | `session.rootOrganizationId` |
| `organizations.organization_type` | `node.organizationType` |
| `organizations.is_seed` | `node.isSeed` |
| `dependencies.source_organization_id` | `edge.sourceOrganizationId` |
| `dependencies.target_organization_id` | `edge.targetOrganizationId` |
| `dependencies.is_seed` | `edge.isSeed` |
| `dependencies.created_at` | `edge.createdAt` |

Database-only fields such as `normalized_name`, `contributor_hash`, internal
round markers, and audit metadata are never exposed in public entities.

## Consistency invariants

- The session root is a seed government node in the same session.
- Seed dependencies have no audience contributor and remain visible in every
  round unless the seed migration changes.
- Audience dependencies belong to exactly one positive round.
- A browser contributes at most one company-profile batch per session round.
- Source and target belong to the dependency's session and cannot be equal.
- Target organization type cannot be `government` through the audience API.
- A contributed company has jurisdiction `europe` and one to three existing,
  distinct European customers.
- A company-profile batch has one to three distinct dependencies and creates
  between two and six directed edges atomically.
- A normalized organization name is unique inside a session.
- One source/target edge is active at most once in a round.
- One `DependencyCreatedEvent` is emitted exactly once for each edge in a
  committed batch and none are emitted for a rolled-back batch.
- HTTP, Realtime, and snapshot representations use identical canonical IDs and
  enum values.

## Contract verification

- Keep TypeScript types and strict Zod schemas together in one shared module.
- Validate every HTTP boundary and every incoming Broadcast payload.
- Add compile-time fixtures using `satisfies` for every success, error, and event
  shape.
- Add contract tests that parse fixtures, endpoint responses, SQL-function
  results, and Broadcast messages with the same schemas.
- CI fails if generated database types or contract fixtures are stale.
