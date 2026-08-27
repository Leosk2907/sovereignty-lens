# Presentation graph workstream plan

Branch: `feature/presentation-graph`

## Outcome

Deliver the main-stage experience: a readable live graph, stable QR invitation,
clear metrics, and a dramatic but truthful hidden-dependency reveal.

## Owned areas

- `/present` route and presentation components
- Cytoscape initialization, styling, layout, and lifecycle
- Pure graph analysis utilities
- Supabase Realtime invalidation subscription and polling fallback
- QR code and participation prompt
- Latest-submission toast, metrics, reveal banner, and connection status
- Presentation-focused tests

Do not change database, endpoints, shared wire contracts, or admin pages.
Develop from committed graph fixtures until the preview API is available.

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
7. Load the authoritative snapshot on mount.
8. Subscribe to filtered Supabase changes. Debounce events and refetch the full
   snapshot rather than mutating graph state directly from database payloads.
9. Detect subscription failure, show degraded status, poll every three seconds,
   and stop polling after Realtime recovers.
10. Compare consecutive snapshots to identify the newest dependency and newly
    reachable external organizations.
11. Animate the shortest newly exposed path, show a reveal banner, and avoid
    replaying the same reveal after unrelated refetches.
12. Add metrics, latest-submission toast, permanent disclaimer, QR code, and
    short instruction.
13. Tune readability at 1920x1080 and a laptop fallback size.

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
- Realtime reconnection and polling fallback do not create duplicate nodes.
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

