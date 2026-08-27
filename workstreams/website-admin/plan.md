# Combined website and admin workstream plan

Branch: `feature/website-admin`

Status: implemented and verified

## Outcome

Deliver every frontend route in one pass. The live graph is `/`; `/admin` uses
the same graph, live-data controller, contract schemas, and visual system with
an authenticated presenter-control sidebar.

## Owned areas

- `/`, `/contribute`, `/about`, `/admin`, and `/present` redirect
- Shared visual system, navigation, and responsive behavior
- Cytoscape graph, analysis, metrics, reveals, and QR invitation
- Realtime Broadcast consumption and persistent snapshot reconciliation
- Browser API clients and contract validation
- Presenter login and controls UI
- Frontend unit, component, and Playwright tests

Backend routes, SQL, cookies, secrets, and database migrations remain owned by
`feature/backend-foundation`.

## Data contract obligations

[`../../contracts/data-contract.md`](../../contracts/data-contract.md) is
authoritative. Import all types and Zod schemas from `src/lib/contracts.ts`.

- Send contract version `1` in every request.
- Parse every HTTP response, structured error, graph snapshot, and Broadcast
  payload before using it.
- Identify graph and admin records by canonical UUID, never name or array index.
- Preserve `source depends on target` everywhere.
- Apply `dependency.created` immediately and idempotently, then reconcile.
- Treat `graph.invalidated` as an authoritative refetch instruction.
- On any mismatch, keep the last valid UI state and refetch.

## Implementation sequence

1. Create Next.js tooling, shared contract schemas, fixtures, API client, and
   browser mock transport.
2. Implement pure graph analysis and the client-only Cytoscape canvas.
3. Implement one `useLiveGraph` controller shared by public and admin modes.
4. Build `/` as the full-screen presentation with metrics, reveal, QR,
   connection health, and disclaimer.
5. Build `/contribute` with search, anonymous identity, 50/50 jurisdiction
   options, strict validation, error mapping, and success state.
6. Build `/about` and disclose the prototype's own dependencies.
7. Build `/admin` with same-route login, shared graph, session controls,
   dependency moderation, URL tools, logout, and reset confirmation.
8. Redirect `/present` to `/`.
9. Add unit/component/end-to-end tests and verify at mobile and presentation
   dimensions.

## Acceptance criteria

- All routes share one coherent visual system and typed API layer.
- `/` and `/admin` render the same graph component and live state controller.
- A committed event appears without manual refresh or duplicate reveal.
- Reconnect and malformed events reconcile from the database snapshot.
- Admin controls cannot be used before authentication.
- Pause, resume, hide, restore, undo, reset, and logout have stable states.
- Contribution behavior follows the canonical contract and one-per-round rule.
- Untrusted names render only as text.
- Lint, typecheck, unit tests, Playwright tests, and production build pass.
