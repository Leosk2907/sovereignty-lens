# Agent instructions

Read `plan.md`, `contracts/data-contract.md`, and the relevant workstream plan
before editing.

## Active ownership

- `feature/backend-foundation` owns database migrations, SQL functions, route
  handlers, admin-cookie utilities, and server secrets.
- `feature/website-admin` owns `/`, `/contribute`, `/about`, `/admin`, shared UI,
  browser API clients, live graph state, and frontend tests.

The previous audience, presentation, and admin-quality workstreams are
superseded by `workstreams/website-admin/plan.md`.

## Contract rules

- `contracts/data-contract.md` is authoritative.
- Import shared types and Zod schemas from `src/lib/contracts.ts`; do not create
  local wire types.
- An edge always means `source depends on target`.
- HTTP and Realtime JSON are camelCase and include `contractVersion: 1`.
- Update the canonical contract before changing a field, enum, endpoint, or
  event meaning.
- Never expose service keys, password values, contributor hashes, or raw
  browser IDs in public graph data.

## Handoff rules

- Keep changes inside the assigned subsystem unless a contract update is agreed.
- Do not commit credentials or production data.
- Run `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` before
  handoff.
- Report test results, known limitations, and the exact commit SHA.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
