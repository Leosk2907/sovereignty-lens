# Sovereignty Lens backend

A standalone Java 21 / Spring Boot 3.3.5 service that holds the whole state of
the live demo: it serves the authoritative graph snapshot, accepts one audience
dependency per phone per round, streams committed graph changes to the
presentation over Server-Sent Events, renders the QR code the audience scans,
and exposes the presenter's recovery controls behind a shared password. It
speaks plain PostgreSQL 16 and nothing else.

**Everything this service stores is simulated, unverified demo data.** Audience
members type company names into a phone; nothing is sourced, checked, or
corroborated. No view built on this API may present a dependency as a factual
allegation about a real organization. Every public surface must carry the
simulated-data disclaimer.

The transport and hosting change that produced this service is recorded in
[`../../contracts/transport-amendment.md`](../../contracts/transport-amendment.md).
The wire contract itself is unchanged at version `1`.

## Architecture at a glance

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full layering and the package
table.

Layers, outermost first: `adapter.web` and `adapter.persistence`, then
`application` (one service per use case, owns the transaction boundary), then
`domain` (pure Java). `contract` holds the frozen version-1 wire records and
`mapper` is the only place a domain model becomes a contract DTO.

The dependency arrow points inward only: adapters know the application layer,
the application layer knows the domain and its ports, and the domain knows
nothing about HTTP, JDBC, Jackson, or Spring.

## Quick start with Docker

This is the path a presenter uses. Everything runs from
`workstreams/backend`. You need Docker Desktop (or Docker Engine plus the
Compose plugin) and nothing else — Java and Maven live inside the build image.

### 1. Create your `.env`

```bash
cd workstreams/backend
cp .env.example .env
```

```powershell
cd workstreams\backend
Copy-Item .env.example .env
```

`.env` is gitignored. It must never be committed and never contains a value you
reuse anywhere else.

### 2. Fill in every variable

Every variable in the template is required. `docker compose up` fails
immediately with a readable message naming any variable that is missing or
empty.

| Variable | What it is |
| --- | --- |
| `POSTGRES_DB` | Database name created on the first start of the `db` container. |
| `POSTGRES_USER` | Database superuser created on that first start. |
| `POSTGRES_PASSWORD` | Password for `POSTGRES_USER`. Any non-empty value works locally; port 5432 is published to `127.0.0.1` only, so it never leaves the machine. |
| `SPRING_DATASOURCE_URL` | JDBC URL the API uses. Over the compose network the host is the service name, not localhost: `jdbc:postgresql://db:5432/<POSTGRES_DB>`. |
| `SPRING_DATASOURCE_USERNAME` | Must match `POSTGRES_USER`. |
| `SPRING_DATASOURCE_PASSWORD` | Must match `POSTGRES_PASSWORD`. |
| `APP_ADMIN_PASSWORD` | The shared presenter password for `/admin`. |
| `APP_AUTH_SECRET` | Signing key for the admin session cookie. At least 32 random bytes. |
| `APP_CONTRIBUTOR_HASH_SECRET` | Server-side key used to hash the anonymous browser UUID before storage. At least 32 random bytes. |
| `APP_PUBLIC_BASE_URL` | Base URL of the audience-facing frontend. **This is what the QR code encodes.** See the warning below. |
| `APP_CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API. Include the LAN origin and, if you develop locally, `http://localhost:3000`. |

Generate the two secrets freshly:

```bash
openssl rand -base64 32
```

```powershell
[Convert]::ToBase64String((1..32 | % { Get-Random -Max 256 }))
```

**`APP_PUBLIC_BASE_URL` must be the LAN address of the presenter laptop, not
`localhost`.** The service encodes this URL into the QR code. On a phone,
`localhost` and `127.0.0.1` mean the phone itself, so a QR code built from them
opens nothing. Use the laptop's LAN IPv4 address, for example
`http://192.168.1.42:3000`.

### 3. Find the LAN address

```bash
bash scripts/lan-url.sh
```

```powershell
./scripts/lan-url.ps1
```

The script prints the LAN IPv4 address and the exact `APP_PUBLIC_BASE_URL` and
`APP_CORS_ALLOWED_ORIGINS` lines to paste into `.env`. It assumes the frontend
is on port 3000 and the API on 8080; pass different ports as arguments
(`bash scripts/lan-url.sh 4000 9090`, or `-FrontendPort` / `-ApiPort` in
PowerShell).

The address changes when the laptop joins a different network. Re-run the script
at the venue, and restart the stack after changing the value.

### 4. Start the stack

```bash
docker compose up --build
```

The first build downloads the Maven dependencies and takes several minutes.
Later starts reuse the cached layers.

### 5. Confirm it is ready

Two services must come up: `sovereignty-lens-db` and `sovereignty-lens-api`.
The API waits for the database health check before it starts, then Flyway
applies the migrations and Spring Boot binds port 8080. The API container has
its own health check with a 60-second grace period, so `docker compose ps`
reports it as `healthy` once the service answers.

```bash
docker compose ps
curl -sS http://localhost:8080/api/health
```

A healthy service answers `/api/health` with a JSON object containing a
`status`, a `version`, and the current server time. Then check that the seeded
graph is there:

```bash
curl -sS http://localhost:8080/api/sessions/demo/graph
```

Finally, check the address a phone will actually use — substitute your own LAN
IP:

```bash
curl -sS http://192.168.1.42:8080/api/health
```

Stop the stack with `Ctrl-C`, or `docker compose down`. `docker compose down -v`
also deletes the database volume and therefore all rounds and contributions.

## Local development without Docker

Requires JDK 21 and Maven 3.9 or newer on the PATH. There is no Maven wrapper in
this repository.

Start only the database container:

```bash
cd workstreams/backend
docker compose up -d db
```

Compose interpolates the whole file even when you name a single service, so
`.env` still has to be complete.

Spring Boot does not read `.env`. Export the variables into the shell yourself,
and point the datasource at `localhost` — the database port is published on
`127.0.0.1:5432`:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sovereignty_lens
export SPRING_DATASOURCE_USERNAME=sovereignty
export SPRING_DATASOURCE_PASSWORD=...
export APP_ADMIN_PASSWORD=...
export APP_AUTH_SECRET=...
export APP_CONTRIBUTOR_HASH_SECRET=...
export APP_PUBLIC_BASE_URL=http://192.168.1.42:3000
export APP_CORS_ALLOWED_ORIGINS=http://192.168.1.42:3000,http://localhost:3000
mvn spring-boot:run
```

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/sovereignty_lens'
$env:SPRING_DATASOURCE_USERNAME = 'sovereignty'
$env:SPRING_DATASOURCE_PASSWORD = '...'
$env:APP_ADMIN_PASSWORD = '...'
$env:APP_AUTH_SECRET = '...'
$env:APP_CONTRIBUTOR_HASH_SECRET = '...'
$env:APP_PUBLIC_BASE_URL = 'http://192.168.1.42:3000'
$env:APP_CORS_ALLOWED_ORIGINS = 'http://192.168.1.42:3000,http://localhost:3000'
mvn spring-boot:run
```

Flyway runs at application startup, not as a separate step. The migrations in
`src/main/resources/db/migration` are applied automatically the first time the
service connects to an empty database, and any pending migration is applied on
every later boot. There is no manual migrate command and no dashboard step.

Two settings, and only two, have a fallback: `SERVER_PORT` defaults to `8080`
and `APP_ROUND_CAPACITY` defaults to `150`. No secret and no URL has one.

## Endpoints

Canonical schemas live in [`openapi/openapi.yaml`](openapi/openapi.yaml).
Copy-pasteable `curl` calls for every endpoint, including error cases, live in
[`docs/api-examples.md`](docs/api-examples.md). While the service is running,
Swagger UI is at `/api/docs` and the generated OpenAPI document at
`/api/openapi`.

`{slug}` is `demo` for this prototype. "Admin cookie" means the signed
`sl_admin` cookie set by the login endpoint.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/health` | none | Liveness and version probe. |
| `GET` | `/api/sessions/{slug}/graph` | none | The authoritative `GraphSnapshot`. |
| `POST` | `/api/sessions/{slug}/dependencies` | none | Submit one audience dependency. |
| `GET` | `/api/sessions/{slug}/events` | none | Server-Sent Events stream of committed graph events. |
| `GET` | `/api/qr` | none | Render the contribution or presentation QR code as PNG or SVG. |
| `POST` | `/api/admin/login` | none | Exchange the shared password for the admin cookie. |
| `POST` | `/api/admin/logout` | none | Clear the admin cookie. Always `204`. |
| `POST` | `/api/admin/sessions/{slug}/actions` | admin cookie | Pause, resume, undo, or reset. |
| `GET` | `/api/admin/sessions/{slug}/dependencies` | admin cookie | Current-round audience dependencies, including hidden ones. |
| `PATCH` | `/api/admin/dependencies/{id}` | admin cookie | Hide or restore one audience dependency. |

Every request and response body carries `contractVersion: 1`. Errors use one
envelope with a fixed code-to-status mapping (`400`, `401`, `403`, `404`, `409`,
`423`, `429`, `500`).

## How a phone joins the demo

1. The presenter starts the stack with `APP_PUBLIC_BASE_URL` set to the
   laptop's LAN address.
2. The presentation requests `GET /api/qr?target=contribute`. The service
   renders a PNG encoding `<APP_PUBLIC_BASE_URL>/contribute` and the
   presentation projects it. The target URL comes from server configuration, so
   a caller cannot influence what the code points at.
3. An audience member scans the code and their phone opens
   `<APP_PUBLIC_BASE_URL>/contribute` — the frontend, not this service.
4. The form calls this API to load the organization list and to `POST` the
   contribution.
5. On success the presentation receives a `dependency.created` event over SSE
   and the node appears without a reload.

The phone and the laptop must be on the same network. Guest Wi-Fi that isolates
clients from each other will not work, however correct the URL is.

The usual failure is a laptop firewall blocking inbound connections on port 8080
(the API) or 3000 (the frontend). On Windows the first `docker compose up`
raises a Windows Defender Firewall prompt for Docker Desktop; dismissing or
denying it leaves the port blocked. How to spot it: `curl` against
`http://localhost:8080/api/health` on the laptop succeeds, but opening
`http://<LAN-IP>:8080/api/health` in the phone's browser hangs and times out.
That combination is a firewall or client-isolation problem, never a wrong
`APP_PUBLIC_BASE_URL` — a wrong base URL fails instantly instead of hanging.

## Database

Migrations are in `src/main/resources/db/migration` and Flyway applies them at
startup.

Three application tables, defined in `V1__schema.sql`:

- `sessions` — one row per demo session. Holds `slug`, `status`
  (`open` / `paused`), `current_round`, and `root_organization_id`.
- `organizations` — nodes. A `normalized_name` is unique inside a session, which
  is what makes two audience members naming the same company reuse one node.
- `dependencies` — edges, read as *source depends on target*. Seed rows have a
  `null` round and stay visible in every round; audience rows belong to exactly
  one round and carry a `contributor_hash`.

Plus one supporting table:

- `graph_events` — the durable, ordered log of every live event. It is what
  makes a contribution and its event atomic (both rows are written in one
  transaction) and what lets a reconnecting SSE client resume from
  `Last-Event-ID`.

Round model: **reset increments `current_round`; it never deletes data.** Graph
reads return the seed dependencies plus the active dependencies of the current
round, and omit nodes that no returned dependency touches. Earlier rounds stay
in the database untouched, so a reset is always recoverable and the same phone
may contribute again in the new round.

The atomic submission function is `submit_dependency` in `V2__functions.sql`.
In one transaction it locks the session row, validates the session, source,
contributor, capacity, self-dependency, and duplicate rules, reuses or creates
the target organization, inserts the dependency, and calls `emit_graph_event`,
which writes the `graph_events` row and issues a transactional `pg_notify`. A
rollback therefore persists neither the dependency nor its event. Domain
failures are raised as custom `SL###` SQLSTATEs that map one-to-one onto the
canonical error codes, so an error can never be reported with a status the
contract does not allow.

## Live updates

`GET /api/sessions/{slug}/events` is a long-lived `text/event-stream` response.
Each message frames the event name in the SSE `event:` field
(`dependency.created` or `graph.invalidated`), the canonical JSON object on one
line in `data:`, and the event's `eventId` in `id:`.

The server writes a comment heartbeat line `: ping` every 15 seconds so that
proxies and idle-timeout middleboxes do not close the stream. Comment lines
carry no event and consumers must ignore them.

Because `id:` is the `eventId`, a browser `EventSource` automatically resends it
as a `Last-Event-ID` header when the connection drops, and the server resumes
from the `graph_events` log rather than losing the gap. Reconnection is the
browser's job; no manual resubscribe is needed.

The stream is per-session, not per-round. The logical topic name
`sovereignty:<sessionSlug>:round:<round>` survives only as a field-level
concept, so a consumer must discard any event whose `sessionSlug` or `round`
does not match what it is currently displaying, and then refetch.

**The snapshot is authoritative; events are only an acceleration hint.**
`GET /api/sessions/{slug}/graph` always wins when local and persistent state
disagree. Consumers apply an event optimistically, deduplicate by `eventId` and
by node/edge id, and then reconcile against a debounced snapshot fetch. They
refetch outright on load, round change, reconnect, any `graph.invalidated`
event, a malformed payload, an unsupported `contractVersion`, or any sequence
uncertainty.

## Testing

```bash
cd workstreams/backend
mvn test      # unit tests; the `integration` group is excluded
mvn verify    # adds the Testcontainers integration tests
```

`mvn verify` needs a running Docker daemon: the integration tests start a real
PostgreSQL container and exercise the actual SQL.

An API smoke suite driven by Playwright is planned under
`workstreams/backend/e2e`:

```bash
cd workstreams/backend/e2e
npm install
npx playwright test
```

The test workstream is producing all three of these. At the time of writing the
`src/test` tree and the `e2e` directory are not in the repository yet, so treat
the commands above as the agreed interface rather than something you can run
today. If a command does not exist when you try it, it is pending, not broken.

## Demo runbook

Work through this before the audience arrives, in order.

1. **Fill `.env`.** Copy `.env.example`, set every variable, and run
   `scripts/lan-url.sh` or `scripts/lan-url.ps1` *on the venue network* to get
   the correct `APP_PUBLIC_BASE_URL` and `APP_CORS_ALLOWED_ORIGINS`.
2. **Start the stack.** `docker compose up --build` from
   `workstreams/backend`.
3. **Verify health locally.** `curl -sS http://localhost:8080/api/health`, then
   `docker compose ps` to confirm both containers are up and the API is
   `healthy`.
4. **Verify health from the LAN address.**
   `curl -sS http://<LAN-IP>:8080/api/health`. If this fails, fix the firewall
   now, not during the talk.
5. **Verify the QR resolves from a real phone.** Scan the projected code with
   an actual handset on the venue Wi-Fi and confirm the contribution form
   loads. Do not accept a laptop-side check as proof; test iOS and Android if
   both will be in the room.
6. **Reset the round.** Log in at `/admin` and run the reset action so the
   audience starts from a clean round. Reset preserves the seed data.
7. **Confirm the seeded graph loads.** The presentation must show the European
   Digital Services Agency root, three fictional European suppliers, and three
   seed edges — and no external dependency at all. Every seed jurisdiction is
   `europe` on purpose: the external reveal has to come from the audience, not
   from data the presenter planted.
8. **Keep the admin URL and password to hand offline.** Write them on paper or
   put them on a second device. If the presentation laptop is the thing that is
   misbehaving, you will not be able to look them up on it.

## Troubleshooting

**The service will not start, and the log names a variable.** This is by
design. No secret and no URL has a fallback, so a deployment that forgets one
fails fast rather than starting with an insecure default. `docker compose up`
refuses before starting anything if a required variable is missing or empty;
`mvn spring-boot:run` fails during context startup. Fill the named variable in
`.env`, or export it, and start again.

**The QR code opens nothing on the phone.** `APP_PUBLIC_BASE_URL` is almost
certainly `localhost` or `127.0.0.1`, which on a phone means the phone itself.
Re-run the `lan-url` helper, put the LAN IPv4 address in `.env`, and restart the
stack — the value is read at startup, so an edit alone changes nothing. If the
URL is already a LAN address and the phone hangs rather than failing instantly,
it is the firewall or client isolation, not the URL.

**The SSE stream drops.** Short drops are normal and the browser reconnects on
its own, resuming from `Last-Event-ID`. Watch the raw stream to confirm the
server side is alive:

```bash
curl -sS -N -H 'Accept: text/event-stream' \
  http://localhost:8080/api/sessions/demo/events
```

You should see a `: ping` comment at least every 15 seconds. If pings arrive but
the presentation still looks stale, the problem is on the consumer side; the
3-second snapshot polling fallback should be covering it. If the connection dies
without pings, look for a proxy or VPN between the two devices.

**Port already in use.** Port 8080 (API) or 5432 (database) is taken by another
process — often a previous stack that was not shut down. Run `docker compose
down`, check what is holding the port (`lsof -i :8080`, or
`Get-NetTCPConnection -LocalPort 8080` in PowerShell), and either free it or
change the published port in `docker-compose.yml`. Note that changing the API
port also changes the URL the phones need.

**Flyway checksum mismatch.** An already-applied migration file was edited.
Flyway compares checksums at startup and refuses to continue. Treat applied
migrations as append-only: restore the file to its applied content and put the
change in a new `V4__...sql` instead. If the database holds nothing you need —
which is normally true before a demo — the fastest fix is to drop it and let
Flyway re-apply everything from scratch:

```bash
docker compose down -v
docker compose up --build
```

`-v` deletes the database volume and every round and contribution in it.

## Security notes

- Secrets are server-side only. `APP_ADMIN_PASSWORD`, `APP_AUTH_SECRET`, and
  `APP_CONTRIBUTOR_HASH_SECRET` live in the process environment, never in a
  browser bundle, a URL, or a log line. `.env` is gitignored; `.env.example`
  carries names and descriptions only.
- The raw browser id is never stored. The anonymous UUID a phone keeps in local
  storage is hashed with `APP_CONTRIBUTOR_HASH_SECRET` before it reaches the
  database, and only the keyed hash is persisted. The API never returns it. No
  IP addresses are stored.
- The presenter password is a single shared secret. A successful login sets the
  signed `sl_admin` cookie — `HttpOnly`, `SameSite=Lax`, and valid for eight
  hours — and every `/api/admin/**` endpoint requires it. A failed login returns
  a deliberately generic `401` that does not reveal which check failed.
- Browsers never connect to PostgreSQL. This service is the only database
  client, which is why row-level security no longer plays any part in the
  design; authorization is enforced in the service layer. The database port is
  published to `127.0.0.1` only. The API port is deliberately published on all
  interfaces, because phones must reach it.

This is a demo-grade posture and should not be mistaken for a production one.
There is no rate limiting beyond the contract's own caps — one contribution per
browser per round, and `APP_ROUND_CAPACITY` (default 150) active audience
dependencies per round. There is no content moderation: the presenter's hide,
undo, and reset controls are the only remedy for an unwanted submission, applied
after the fact. There are no per-user accounts, no roles, and no audit trail
beyond `graph_events`. A production version would need authenticated clients,
real rate limiting, moderation, and a verification workflow for every claim.
