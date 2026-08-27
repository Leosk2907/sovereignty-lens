# Backend and foundation workstream plan

Branch: `feature/backend-foundation`

> **Architecture amendment.** This plan was written against Next.js route
> handlers on Vercel with Supabase. The backend is now a standalone Java 21 /
> Spring Boot 3.3.5 service built with Maven, using plain PostgreSQL 16 and
> Server-Sent Events in place of Supabase Realtime. Transport and hosting
> changed; the wire contract did not. See
> [`../../contracts/transport-amendment.md`](../../contracts/transport-amendment.md).
> Contract version stays `1`.

## Outcome

Deliver the standalone backend service, a reproducible PostgreSQL schema, seed
data, secure HTTP endpoints, and a Docker Compose stack a presenter can start on
a laptop. Frontend owners must be able to work against the committed OpenAPI
document and fixtures before a running backend is available to them.

## Owned areas

- Project/tooling configuration and fail-fast environment validation
- The frozen version-`1` contract records in `eu.sovereigntylens.contract`, and
  the OpenAPI document generated from them
- Spring JDBC repositories and row mappers behind the domain ports
- Flyway migrations, seed migration, indexes, the least-privilege application
  role, atomic submission, and the durable `graph_events` log
- Public graph and contribution controllers
- The Server-Sent Events stream and its `pg_notify` bridge
- QR-code rendering from `APP_PUBLIC_BASE_URL`
- Admin authentication, admin action and moderation controllers
- Backend unit/integration tests
- CI, the Dockerfile, and the Docker Compose stack

Do not implement the audience, Cytoscape, or admin page UI.

## Data contract obligations

[`../../contracts/data-contract.md`](../../contracts/data-contract.md), as
amended by
[`../../contracts/transport-amendment.md`](../../contracts/transport-amendment.md),
is authoritative. This workstream owns the Java implementation of the wire
format — the records and enums in `eu.sovereigntylens.contract` — but may not
change its semantics without a coordinated contract update. The contract package
is a published artefact shared with three frontend workstreams; the domain layer
carries its own enums and error codes so that business rules and wire format can
be versioned independently.

- Accept `ContributionRequest`, `AdminLoginRequest`, `AdminActionRequest`, and
  `DependencyStatusRequest` exactly as versioned, strictly parsed bodies. An
  unknown field is a contract violation, not noise.
- Return `GraphSnapshot`, `ContributionResult`, `AdminLoginResult`,
  `AdminActionResult`, `AdminDependencyList`, `DependencyStatusResult`, or
  `ApiErrorResponse` with the documented status mapping, decided in exactly one
  place (`mapper.ErrorMapper`).
- Emit only `DependencyCreatedEvent` and `GraphInvalidatedEvent`, on the
  per-session Server-Sent Events stream. The logical topic name
  `sovereignty:<sessionSlug>:round:<round>` survives as a field-level concept
  only, so every event still carries `sessionSlug` and `round` for consumer-side
  filtering.
- Serialize public JSON as camelCase and map it explicitly from snake_case
  database rows.
- Never expose normalized names, contributor hashes, internal round markers,
  secrets, or other database-only fields.
- Preserve the invariant that an edge means `source depends on target`.
- Publish the OpenAPI document and worked request/response examples for every
  endpoint, error, and event so other owners can develop without a live backend.

## Ordered tasks

1. Scaffold the Maven project on Java 21 and Spring Boot 3.3.5 with the web,
   JDBC, validation, actuator, Flyway, and springdoc dependencies, laid out in
   the layers described in [`ARCHITECTURE.md`](ARCHITECTURE.md).
2. Wire the build: Surefire for unit tests, Failsafe for the `integration`
   group, and the Spring Boot plugin for the runnable jar.
3. Commit `.env.example` and bind configuration through `AppProperties` so a
   missing secret fails startup. No secret and no URL may have a fallback.
4. Implement the exact canonical data contract as records and enums in one
   frozen `contract` package, with strict Jackson parsing.
5. Commit the OpenAPI document and worked `curl` examples covering every
   endpoint, error, and event shape.
6. Write the Flyway migrations creating the three tables plus `graph_events`,
   with constraints, partial unique indexes, timestamp behavior, and the
   least-privilege application role.
7. Seed one `demo` session rooted at `European Digital Services Agency`, three
   fictional European suppliers, and connected seed dependencies.
8. Implement the transactional submission SQL function. In the same transaction,
   insert the dependency, write the versioned canonical `dependency.created`
   payload to `graph_events`, and `pg_notify` the SSE bridge.
9. Emit `graph.invalidated` from admin mutation transactions for pause, resume,
   hide, restore, undo, and reset.
10. Implement graph loading with seed plus current-round active dependencies.
11. Implement the SSE endpoint: `text/event-stream`, `id:`/`event:`/`data:`
    framing, a `: ping` heartbeat every 15 seconds, and `Last-Event-ID` resume
    served from the `graph_events` log.
12. Implement all controllers and map domain errors to the specified HTTP status
    codes.
13. Implement constant-time presenter-password comparison, signed `sl_admin`
    cookie creation/verification, server-only contributor hashing, and
    configured CORS.
14. Render the contribution and presentation QR codes from
    `APP_PUBLIC_BASE_URL`, and ship the `lan-url` helper scripts that produce
    the correct LAN value.
15. Add backend tests, including rollback behavior, and a GitHub Actions
    workflow.
16. Ship the Dockerfile and Docker Compose stack, and hand the other workstreams
    a documented way to start the API on a laptop and reach it from the LAN.

## Seed scenario

Use only obviously fictional entities:

- European Digital Services Agency — government root, Europe
- Alpine Civic Systems — software, Europe
- Baltic Data Works — cloud, Europe
- Rhine Public Networks — telecom, Europe

Seed only European dependencies. The external reveal must come from audience
participation.

## Acceptance criteria

- A clean clone can build, test, and start from documented commands, with
  Docker as the only prerequisite for the presenter path.
- Migrations create a complete empty environment with no manual or dashboard-only
  steps. Flyway applies them at service startup.
- Reapplying migrations is safe, and an applied migration is never edited in
  place.
- Committed examples and fixtures conform to the same contract records the
  controllers serialize.
- Contract tests prove database rows, SQL results, API bodies, and SSE payloads
  map to the canonical version `1` shapes, and that the domain and contract
  enum sets stay in step.
- Every endpoint returns a consistent JSON success/error envelope.
- Submission is atomic under concurrent requests.
- A committed submission produces exactly one versioned `dependency.created`
  event with identifiers matching the API response and stored rows.
- A rolled-back submission produces neither a stored dependency nor a live
  event.
- **Replaces "Anonymous database clients cannot insert, update, or delete."**
  Browsers no longer connect to PostgreSQL at all, so row-level security has
  nothing to guard and the criterion no longer applies as written. The
  equivalent guarantee is now: this service is the only database client, it
  connects with a least-privilege application role holding `SELECT`, `INSERT`,
  and `UPDATE` on the three application tables and nothing else, and every
  `/api/admin/**` endpoint rejects a request without a valid signed `sl_admin`
  cookie. Authorization is enforced in the service layer and proven by tests
  there.
- Database and presenter secrets never enter a response body, a client bundle,
  or a log line. The raw anonymous browser id is never persisted, only its keyed
  hash.
- **Replaces "Preview deployment returns the seeded graph and accepts a valid
  dependency."** There is no hosted preview environment; the demo runs on the
  presenter's laptop. The equivalent guarantee is now: `docker compose up
  --build` from a filled `.env` reaches a healthy `/api/health`, returns the
  seeded graph, accepts a valid dependency, and delivers the resulting
  `dependency.created` frame on the SSE stream — and all of that works from a
  phone on the same network via the LAN address, not only from the laptop.
- All owned tests and CI checks pass before handoff.

## Handoff

Provide the start command and the LAN base URL the other workstreams should call,
the environment variable checklist from `.env.example`, confirmation that
migrations need no separate command because Flyway runs them at startup, the
paths to `openapi/openapi.yaml` and `docs/api-examples.md`, known limitations,
and the commit SHA to the integration owner. [`README.md`](README.md) is the
operational document; keep it in step with what actually ships.
