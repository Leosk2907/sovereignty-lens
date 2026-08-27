# Presentation graph workstream plan

Branch: `feature/presentation-graph`

## Outcome

Deliver the main-stage experience: a readable live graph, stable QR invitation,
clear metrics, and a dramatic but truthful hidden-dependency reveal.

## Owned areas

- `/present` route and presentation components
- Cytoscape initialization, styling, layout, and lifecycle
- Pure graph analysis utilities
- Supabase Realtime Broadcast subscription, immediate event application,
  snapshot reconciliation, and polling fallback
- QR code and participation prompt
- Latest-submission toast, metrics, reveal banner, and connection status
- Presentation-focused tests

Do not change database, endpoints, shared wire contracts, or admin pages.
Develop from committed graph fixtures until the preview API is available.

## Data contract obligations

[`../../contracts/data-contract.md`](../../contracts/data-contract.md) is
authoritative. This workstream consumes `GraphSnapshot` from HTTP and
`GraphEvent` from Broadcast.

- Import the shared types and Zod schemas; do not recreate graph or event types.
- Accept only contract version `1` and the current `sessionSlug`/`round`.
- Apply `DependencyCreatedEvent.node` and `.edge` by canonical IDs, never array
  position or company name.
- Treat `GraphInvalidatedEvent` as a refetch instruction, not a graph mutation.
- Preserve `source depends on target` when mapping arrow direction.
- Use only contract enum values for colors and external-exposure rules.
- Never display hidden dependencies, database-only fields, or raw unvalidated
  Broadcast payloads.
- On any contract mismatch, keep the last good graph and fetch `GraphSnapshot`.

## Ordered tasks

1. Implement pure breadth-first graph analysis that returns reachable IDs,
   depth, predecessor map, maximum depth, reachable external IDs, and shortest
   path reconstruction.
2. Test branches, multiple external nodes, disconnected nodes, self-loops, and
   cycles before connecting visualization code.
3. Build a client-only Cytoscape wrapper with explicit creation and cleanup.
4. Map shared graph nodes/edges into Cytoscape without putting untrusted values
   into HTML.
5. Use a root-oriented breadth-first layout and rerun it with bounded animation
   after topology changes. Preserve user zoom when practical.
6. Implement jurisdiction styles and arrow direction exactly as specified.
7. Load the authoritative snapshot on mount and subscribe to
   `sovereignty:demo:round:<round>` Broadcast events.
8. Validate every versioned `GraphEvent`. Apply `dependency.created` node/edge
   payloads to local graph state immediately and idempotently, then schedule a
   debounced authoritative snapshot reconciliation. Refetch immediately for
   `graph.invalidated`, malformed, or wrong-round events.
9. Keep a bounded processed-event-ID set so reconnect or replay behavior cannot
   duplicate nodes, edges, toasts, or reveal animations.
10. Detect subscription failure, show degraded status, poll every three seconds,
   and stop polling after Realtime recovers.
11. Compare consecutive committed states to identify the newest dependency and newly
    reachable external organizations.
12. Animate the shortest newly exposed path, show a reveal banner, and avoid
    replaying the same reveal after unrelated refetches.
13. Add metrics, latest-submission toast, permanent disclaimer, QR code, and
    short instruction.
14. Tune readability at 1920x1080 and a laptop fallback size.

## Visualization rules

- Root is visually unique and remains easy to locate.
- Europe is blue, US red, China orange, other external purple, unknown gray.
- Seed and audience nodes may differ subtly, but jurisdiction remains the
  dominant color signal.
- Active reveal nodes/edges pulse or glow for a bounded duration; do not leave
  animation running indefinitely.
- Labels truncate visually but expose the full text through selection or a
  detail panel.
- Avoid physics that causes continuous motion during the pitch.
- Unknown nodes are visible but are not counted as external exposure.

## Acceptance criteria

- The seeded graph renders without layout overlap that hides labels.
- One database change appears without a manual refresh.
- Every HTTP snapshot and Broadcast fixture parses through the canonical shared
  schema before it reaches graph state.
- Realtime reconnection and polling fallback do not create duplicate nodes.
- A valid committed Broadcast updates the graph before snapshot reconciliation,
  and the snapshot creates no visual duplicate or second reveal.
- A malformed or fabricated Broadcast cannot remain after reconciliation.
- A reachable US/China/other-external node highlights a correct shortest path.
- Europe and unknown do not create a reveal banner.
- Cycles never freeze rendering or analysis.
- Refreshing reconstructs the graph and does not falsely replay an old reveal.
- The QR code uses the current production/preview origin and resolves to
  `/contribute`.
- The simulated-data disclaimer is visible at presentation distance.
- A scripted 50-contribution fixture remains responsive and readable.
- Owned tests pass.

## Handoff

Provide a screen recording of an external reveal, 1920x1080 screenshot, fixture
performance notes, test output, known limitations, and commit SHA to the
integration owner.
