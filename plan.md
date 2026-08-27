# Sovereignty Lens implementation plan

Status: approved for implementation

This file is the canonical delivery plan. The active workstream plans under
`workstreams/` refine ownership and sequencing but may not change the shared
contracts in this document without an explicit integration decision.

## Goal

Build and deploy an audience-participation prototype that demonstrates how a
fictional European governmental body can be indirectly dependent on companies
outside Europe.

The presenter displays a live graph. Audience members scan a QR code, add their
European company, connect one to three existing European customers, and name
one to three providers it depends on. When a directed path from the fictional
government body reaches a non-European company, the presentation highlights the
complete hidden dependency chain.

All submitted data is simulated, unverified demo data. The application must
never present it as a factual allegation.

## Delivery checkpoints

- [x] Checkpoint 1: Create the public GitHub repository and clone it locally.
- [ ] Checkpoint 2: Scaffold Next.js, shared contracts, database migrations,
  seed data, environment template, CI, and agent instructions.
- [ ] Checkpoint 3: Merge a working backend and deploy a preview environment.
- [x] Checkpoint 4: Merge the combined website and admin frontend.
- [x] Checkpoint 5: Complete automated end-to-end tests.
- [ ] Checkpoint 6: Deploy production, rehearse the complete demo, reset the
  production round, and tag the exact commit as `demo-ready`.

## Definition of done

- The production presentation view shows a seeded fictional European network
  and a QR code pointing to the production contribution URL.
- A phone can submit one atomic company-profile batch per demo round.
- A successful submission appears on the presentation without a manual reload.
- A reachable non-European dependency highlights the shortest path from the
  governmental root and displays a clear reveal message.
- A presenter can log in, pause/resume, hide/restore, undo, and reset.
- Resetting starts a new round while preserving fictional seed data.
- The UI labels audience entries as simulated and unverified.
- Mobile and 1920x1080 presentation layouts are usable.
- Lint, typecheck, unit tests, end-to-end tests, and production build pass.
- The repository contains reproducible setup, migration, deployment, and demo
  rehearsal instructions, with no committed secrets.

## Scope

### Included

- One public demo session with multiple resettable rounds
- Fictional seed organizations and dependencies
- Mobile contribution form
- Large-screen live graph
- Shortest-path exposure detection and reveal animation
- Shared-password presenter administration
- Supabase persistence and Realtime updates
- Vercel preview and production deployment
- Basic validation, contribution limits, and presenter recovery controls

### Excluded from this sprint

- Real claims about governments or suppliers
- Automated procurement ingestion or web research
- Source/evidence verification workflows
- User accounts, roles, or organization tenancy
- AI-generated dependencies
- A general-purpose graph editor
- Production-grade moderation, fraud prevention, or analytics

## User experience

### Presentation route: `/`

- Use a refined deep-ink interface dominated by the interactive graph. Keep
  chrome minimal, place the question `What does Europe depend on?` inside the
  graph, and use one coherent cool palette without red or orange.
- Root the graph at the fictional **European Digital Services Agency**.
- Show directed arrows with the semantic `source depends on target`.
- Keep a QR code and short participation instruction visible.
- Use these visual groups: government body in cold white, Europe in blue, all
  external jurisdictions in violet, and unknown in slate. Exact jurisdiction
  remains available on node focus.
- Show only the reachable external count publicly; keep operational metrics in
  admin.
- Animate ordinary contributions directly in the graph without a toast.
- When a newly reachable external organization appears, animate the shortest
  path and show a message such as `Hidden dependency revealed: 3 steps to a US
  provider`.
- Always show `Simulated, audience-submitted demo data`.
- Show connection health. If Realtime disconnects, continue by polling every
  three seconds until the subscription reconnects.

### Contribution route: `/contribute`

- Use the company-profile flow from the contribution prototype in three compact
  numbered sections: `Your company`, `Your customers`, and `Your dependencies`.
- Collect a new European company name and organization type; its jurisdiction is
  fixed to Europe for this demo.
- Let the user search and choose one to three existing European organizations
  that depend on the contributed company.
- Collect one to three providers with name, organization type, and jurisdiction;
  offer quick picks for familiar external providers to accelerate the live demo.
- Assign each browser an anonymous UUID in local storage.
- Submit the entire profile as one atomic batch and accept one batch per browser
  per round.
- After success, replace the form with a confirmation that asks the user to
  watch the presentation.
- Give useful states for invalid input, duplicate dependency, already
  contributed, paused session, full round, network error, and retry.

### Administration route: `/admin`

- Authenticate with one shared presenter password.
- Set a signed, HTTP-only, same-site admin session cookie.
- Show current round, session state, submission count, and live connection
  health.
- Provide pause, resume, undo latest, and reset round actions.
- List current-round audience dependencies with hide/restore actions.
- Require confirmation for reset.
- Provide buttons to copy/open presentation and contribution URLs.

### About route: `/about`

- Explain direct versus transitive dependencies.
- Explain that production data would need sources, verification, procurement
  records, software bills of materials, corporate ownership information, and
  access controls.
- Disclose Vercel and Supabase as dependencies of this prototype to reinforce
  the product's own transparency principle.

## Technical architecture

- Next.js App Router, TypeScript, React, and Tailwind CSS
- Vercel for application hosting and preview deployments
- Supabase PostgreSQL for durable state
- Supabase Realtime Broadcast for low-latency committed graph events
- Cytoscape.js for rendering and laying out the graph
- Motion for React for restrained form, reveal, and admin transitions
- Zod for shared request and response validation
- `qrcode.react` for the QR code
- Vitest and React Testing Library for unit/component tests
- Playwright for critical end-to-end flows
- GitHub Actions for lint, typecheck, unit tests, and production build

Use one Next.js repository and one deployment. Next.js route handlers are the
backend. The Supabase service-role key is server-only. Browser clients receive
read-only access needed for graph loading and Realtime; they never write tables
directly. Vercel handlers do not hold presentation WebSocket connections;
Supabase is the shared live-delivery layer across function instances.

## Shared data contract

[`contracts/data-contract.md`](contracts/data-contract.md) is the sole source of
truth for database-to-JSON mapping, enums, graph entities, requests, responses,
errors, admin records, and Realtime events. All payloads use contract version
`1`, camelCase HTTP/Realtime JSON, UUID identifiers, and UTC RFC 3339 timestamps.

The implementation must provide the contract's TypeScript types and strict Zod
schemas from one shared module. Server and client code import that module and do
not redefine wire shapes. Contract tests parse SQL-function results, API
responses, fixtures, and Broadcast events with those same schemas.

Every workstream plan contains an explicit contract-obligations section. A
breaking field, enum, direction, or meaning change requires updating the
canonical contract first and coordinating all workstreams.

## Database model

### `sessions`

- `id uuid primary key`
- `slug text unique not null`
- `title text not null`
- `status text not null check (status in ('open', 'paused'))`
- `current_round integer not null default 1`
- `root_organization_id uuid`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`

### `organizations`

- `id uuid primary key`
- `session_id uuid not null references sessions(id)`
- `name text not null`
- `normalized_name text not null`
- `organization_type text not null`
- `jurisdiction text not null`
- `is_seed boolean not null default false`
- `created_at timestamptz not null default now()`
- Unique `(session_id, normalized_name)`

### `dependencies`

- `id uuid primary key`
- `session_id uuid not null references sessions(id)`
- `round integer` for audience rows; seed rows use `null`
- `source_organization_id uuid not null references organizations(id)`
- `target_organization_id uuid not null references organizations(id)`
- `contribution_id uuid references contributions(id)` for audience rows
- `is_seed boolean not null default false`
- `status text not null check (status in ('active', 'hidden'))`
- `created_at timestamptz not null default now()`
- Unique active source/target pair per session and round

### `contributions`

- `id uuid primary key`
- `session_id uuid not null references sessions(id)`
- `round integer not null`
- `contributor_hash text not null`
- `company_organization_id uuid not null references organizations(id)`
- `created_at timestamptz not null default now()`
- Unique `(session_id, round, contributor_hash)`

Create an atomic PostgreSQL function for company-profile submission. It validates
that the session is open, all one-to-three customers are distinct active European
nodes, the new European company name is unused, all one-to-three dependencies
are distinct, the contributor has not submitted in the round, and the resulting
batch stays below the 150-edge round limit. It inserts the contribution and
company, normalizes/upserts provider organizations, creates customer-to-company
and company-to-provider edges, and calls `realtime.send()` once per edge with the
canonical `DependencyCreatedEvent`. All writes and Realtime-message inserts share
one transaction. A rollback therefore persists neither the profile nor any of
its live events.

Admin mutations emit `graph.invalidated` from their database transaction. They
do not carry full graph state because the presentation refetches after these
less frequent actions.

Reset increments `current_round`; it does not delete old data. Graph reads return
seed dependencies plus active dependencies from the current round. Nodes not
incident to those dependencies are omitted.

## HTTP API

All request, success, and error bodies conform to contract version `1` in the
canonical data contract. API JSON is camelCase even though PostgreSQL is
snake_case.

### `GET /api/sessions/demo/graph`

Returns `GraphSnapshot`. It is the authoritative graph read used at initial
load, after Realtime invalidations, and during polling fallback.

### `POST /api/sessions/demo/company-contributions`

Accepts `CompanyContributionRequest`. Hash `anonymousClientId` with a server
secret before storage. The handler awaits the atomic database function; it does
not use a fire-and-forget persistence promise. On success it returns
`CompanyContributionResult`; every connection has the same canonical event ID,
node, and edge as its Broadcast event. Return:

- `201` with the canonical company and all created connections
- `400` invalid profile, repeated names, self-dependency, or non-European customer
- `404` session/customer not found
- `409` duplicate relationship, existing company name, or browser already contributed
- `423` session paused
- `429` current round at capacity

### `POST /api/admin/login`

Accepts `{ password: string }`, validates against `ADMIN_PASSWORD`, and creates
the signed admin cookie using `AUTH_SECRET`. Return a generic unauthorized error
without revealing which check failed.

### `GET /api/admin/session`

Returns the authenticated presenter state and current `SessionSummary`, or the
canonical `401` error.

### `POST /api/admin/logout`

Clears the signed presenter cookie and returns `AdminLogoutResult`.

### `POST /api/admin/sessions/demo/actions`

Accepts `AdminAction`. Requires the admin cookie. Pause/resume update session
status, undo hides the latest active audience dependency, and reset increments
the round and reopens the session.

### `GET /api/admin/sessions/demo/dependencies`

Requires the admin cookie and returns `AdminDependencyList`: all current-round,
non-seed dependencies including hidden entries, newest first.

### `PATCH /api/admin/dependencies/:id`

Accepts `{ status: "active" | "hidden" }`, requires admin authentication, and
only modifies a current-round non-seed dependency.

## Live delivery and persistence

Use one Realtime topic per session and round:
`sovereignty:demo:round:<round>`. The presentation subscribes to Broadcast over
WebSocket before showing a healthy/live indicator.

The contribution flow is:

1. A phone posts `CompanyContributionRequest` to the Next.js endpoint.
2. The endpoint validates the request and calls the atomic database function.
3. PostgreSQL creates the profile and all edges and inserts one
   `dependency.created` Broadcast message per edge in the same transaction.
4. Once committed, Supabase streams those events to the presentation channel.
5. The presentation validates each `GraphEvent`, inserts its canonical node/edge
   into local graph state immediately, calculates exposure, and runs the reveal.
6. The presentation schedules a debounced `GET` snapshot reconciliation. The
   database snapshot always wins if local and persistent state differ.
7. The phone receives the canonical batch response and shows success.

Broadcast and the HTTP response may arrive in either order; they share the same
dependency/event identifiers and must be idempotent. The presentation keeps a
bounded set of processed event IDs and deduplicates nodes/edges by ID.

For this no-login sprint demo, use a public receive channel. Browser UI never
sends Broadcast events, but public channels are not an authorization boundary.
Consequently every event is treated as an acceleration hint and reconciled with
the authoritative database immediately. A production version must use
authenticated private channels with receive-only Realtime RLS policies.

At initial load, round change, reconnect, invalidation event, malformed event,
or sequence uncertainty, fetch a complete `GraphSnapshot`. If Realtime is not
subscribed, poll every three seconds. Stop fallback polling after a successful
subscription and reconciliation.

## Graph behavior

- Treat an edge as directed from the depending organization to its dependency.
- Run breadth-first search from the root over active directed edges.
- Record predecessor and depth during traversal.
- External jurisdictions are `united_states`, `china`, and `other_external`.
- `unknown` is visually unresolved but does not trigger an exposure warning.
- For each reachable external node, reconstruct its shortest root path from the
  predecessor map.
- Compare previous and next committed graph states, whether received by Broadcast
  or snapshot. If a new reachable external node appears, animate its shortest
  path; otherwise update without a reveal banner.
- Handle cycles with a visited set and never assume the graph is a tree.

## Validation and safety

- Trim names, normalize Unicode, collapse internal whitespace, and compare a
  lowercase normalized form for deduplication.
- Company names are 2-60 characters.
- Render all audience strings as text. Never use untrusted HTML.
- Do not persist raw browser UUIDs or IP addresses.
- RLS allows public reads needed by the demo and denies anonymous writes.
- Validate every live payload with the shared `GraphEvent` Zod schema.
- Realtime is never the durable source of truth; reconnect and recovery always
  use `GraphSnapshot`.
- The service-role key, admin password, hash secret, and auth secret remain in
  server environment variables only.
- The contributed company is always European; dependency providers accept every
  canonical jurisdiction.
- Keep the simulated-data disclaimer visible in every public graph view.

## Team workflow

The initial scaffold and shared contracts land on `main` first. Then each owner
works on a short-lived branch described in its workstream plan:

- `feature/backend-foundation`
- `feature/website-admin`

The integration owner controls shared domain types, global configuration,
database contracts, and merges. Workstream owners must not silently change API
or database contracts. Contract changes are proposed and landed centrally
before dependent implementations change.

Merge order:

1. Scaffold, fixtures, migrations, and contracts
2. Backend/database
3. Combined website and admin frontend
4. Integration fixes, production deployment, and rehearsal

## Test plan

### Unit and component tests

- Breadth-first traversal returns reachable nodes, depths, and shortest paths.
- Cyclic input terminates safely.
- Europe and unknown do not trigger external exposure.
- Every external jurisdiction triggers exposure when reachable.
- Input normalization catches equivalent company names and duplicate edges.
- Company-profile validation covers batch limits, distinct names and customers,
  the Europe-only company rule, and all documented status codes.
- A successful transaction stores the full batch and emits exactly one matching
  `DependencyCreatedEvent` per edge; a rollback stores and emits nothing.
- Repeated event IDs and edge IDs are applied idempotently.
- Invalid, wrong-round, and unsupported-version events trigger reconciliation
  without changing durable graph state.
- Admin-cookie validation rejects missing, expired, malformed, and invalid
  signatures.
- The form creates one European company with one-to-three European customers and
  one-to-three dependency providers.
- Presentation and contribution states render accessible status messages.

### End-to-end tests

- Load the seeded graph and verify QR/instructions.
- Submit a profile with only European dependencies and observe it without an
  exposure warning.
- Add a multi-hop US dependency and observe the complete reveal path.
- Reuse an existing target company from two source organizations without
  creating a duplicate organization.
- Reject a second submission from the same browser in one round.
- Pause and verify submissions fail; resume and verify they work.
- Hide/restore and undo a contribution.
- Reset, preserve seed data, clear audience data from the visible graph, and let
  the same browser contribute in the new round.
- Refresh presentation and reconstruct current state.
- Simulate Realtime loss and verify polling fallback.
- Deliver a live event followed by its snapshot and verify only one node, edge,
  toast, and reveal are rendered.
- Render untrusted HTML-like company text harmlessly.

### Rehearsal

- Test the production QR code on iOS and Android.
- Test at 1920x1080 on the actual presentation browser.
- Submit at least ten real-device contributions and run a scripted 50-client
  load simulation.
- Verify admin recovery actions while the presentation remains open.
- Reset immediately before the final presentation.
- Record the production URL and admin recovery steps somewhere available
  offline.

## Environment variables

```text
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=
ADMIN_PASSWORD=
AUTH_SECRET=
CONTRIBUTOR_HASH_SECRET=
```

No variable may have a functional fallback in production. `.env.example`
contains names and descriptions only, never real values.
