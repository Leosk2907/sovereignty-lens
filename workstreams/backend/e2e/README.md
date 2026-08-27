# End-to-end API smoke suite

Playwright smoke tests for the Sovereignty Lens backend. This suite is
**API-level only**: it drives `http://localhost:8080` with Playwright's
`APIRequestContext` and Node's `fetch`, and never opens a browser or touches a
UI.

- Canonical schemas: [`../openapi/openapi.yaml`](../openapi/openapi.yaml)
- Wire contract: [`../../../contracts/data-contract.md`](../../../contracts/data-contract.md)
- SSE framing: [`../../../contracts/transport-amendment.md`](../../../contracts/transport-amendment.md)
- Worked `curl` calls: [`../docs/api-examples.md`](../docs/api-examples.md)

## Warning: this suite mutates the demo session

Every spec writes to the one shared `demo` session on whatever stack
`API_BASE_URL` points at. It pauses and resumes the session, submits
contributions, hides and restores dependencies, runs `undo`, and calls
**`reset` between scenarios** to isolate them.

**Never point this suite at a live presentation.** A reset mid-demo would clear
the audience graph off the projector. Run it against a local `docker compose`
stack, or against a preview environment nobody is presenting from.

Reset is non-destructive at the data level — it increments `currentRound` and
leaves seed data untouched — but it does change what the presentation view
shows, immediately.

## Prerequisites

1. **Start the stack first.** The suite never boots the backend for you and
   there is no `webServer` block in the Playwright config.

   ```bash
   cd workstreams/backend
   cp .env.example .env      # then fill in the values
   docker compose up
   ```

   Wait until `curl http://localhost:8080/api/health` returns `{"status":"ok",...}`.

2. **Export the presenter password.** The admin specs log in with it. It is
   never hardcoded and never committed; set it to the same value as
   `APP_ADMIN_PASSWORD` in `workstreams/backend/.env`.

   ```bash
   # bash / Git Bash / WSL
   export ADMIN_PASSWORD='<the APP_ADMIN_PASSWORD value from .env>'
   ```

   ```powershell
   # PowerShell
   $env:ADMIN_PASSWORD = '<the APP_ADMIN_PASSWORD value from .env>'
   ```

   `APP_ADMIN_PASSWORD` is also accepted, so exporting the backend's own
   variable name works too. If neither is set, the admin specs fail immediately
   with an explanatory message rather than guessing a password.

## Run

```bash
cd workstreams/backend/e2e
npm install
npx playwright test          # or: npm test
```

No browsers are needed, so `npx playwright install` is not required.

Useful variants:

```bash
npx playwright test tests/contribution.spec.ts   # one spec
npx playwright test -g "reset"                   # one scenario by title
npm run report                                   # open the last HTML report
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `API_BASE_URL` | `http://localhost:8080` | Backend under test. |
| `ADMIN_PASSWORD` | — | Presenter password. Falls back to `APP_ADMIN_PASSWORD`. Required by the admin and SSE specs. |
| `CI` | — | When set, enables 2 retries and `forbidOnly`. |

```bash
API_BASE_URL=https://preview.example.test npx playwright test
```

## What is covered

| Spec | Scope |
| --- | --- |
| `tests/graph.spec.ts` | Health probe, snapshot shape, seeded graph, the "every edge endpoint is a node in the same snapshot" invariant, deterministic ordering, unknown slug. |
| `tests/contribution.spec.ts` | Happy path and its snapshot round-trip, organization reuse across sources including case/whitespace variants, one submission per browser per round, duplicate edges, self-dependency, `government` target, unknown source, name-length boundaries, wrong `contractVersion`, strict unknown-field rejection, HTML-shaped company text. |
| `tests/admin.spec.ts` | Generic 401s, cookie enforcement, pause/resume, hide/restore, undo, moderation-list ordering, reset semantics. |
| `tests/events.spec.ts` | `dependency.created` matching the HTTP 201 byte for byte, `graph.invalidated` reasons, and the SSE `id:` field equalling `eventId`. |

## Conventions

- **Assert on `error.code`, never on `error.message`.** The message is human
  copy for an audience member and may be rewritten at any time; the code and its
  HTTP status are fixed by the data contract.
- **Every test is repeatable against an already-running stack.** State is reset
  in `beforeEach`/`beforeAll`; nothing assumes a virgin database.
- **Strictly serial.** `fullyParallel: false` plus `workers: 1`, because all
  tests share one session.
- **The seeded UUIDs are literals** from
  `../src/main/resources/db/migration/V3__seed_demo_session.sql`, re-exported
  from `tests/helpers.ts`. If that migration changes, update `SEED` there.
- TypeScript, ESM, Playwright's built-in `expect`, no runtime dependencies
  beyond `@playwright/test`.

## Notes on the SSE tests

`APIRequestContext` buffers a full response body before handing it back, which
never finishes for a long-lived `text/event-stream`. `readSseEvents()` in
`tests/helpers.ts` therefore uses Node's `fetch` and iterates `response.body`,
parsing the framing incrementally: blank-line-delimited records, `id:` /
`event:` / `data:` fields, repeated `data:` lines joined with a newline, and
`: ping` heartbeat comment lines ignored.

Its `onOpen` callback fires once the stream is open, so the test triggers the
very action it is waiting for without racing the connection.

## Generated files

`node_modules/`, `test-results/`, and `playwright-report/` are already ignored
by the repository's root `.gitignore`. Do not commit them.
