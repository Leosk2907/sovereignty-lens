# Audience contribution workstream plan

Branch: `feature/audience-flow`

## Outcome

Deliver a fast, accessible mobile flow that lets a first-time audience member
add exactly one understandable dependency and receive clear confirmation.

## Owned areas

- `/contribute` route and audience-facing components
- Anonymous browser identity storage
- 50/50 jurisdiction-option mechanic
- Source organization search/selection
- Contribution API client and UI error mapping
- Mobile component tests and audience-flow Playwright coverage

Do not change database, endpoint, shared-contract, graph, or admin behavior.
Develop against the committed fixtures until the preview API is ready.

## Data contract obligations

[`../../contracts/data-contract.md`](../../contracts/data-contract.md) is
authoritative. This workstream consumes `GraphSnapshot` for the source selector,
produces `ContributionRequest`, and handles `ContributionResult` or
`ApiErrorResponse`.

- Import shared types and Zod schemas; do not declare local payload interfaces.
- Send `contractVersion: 1`, a UUID `anonymousClientId`, the selected canonical
  source ID, and the strict target object.
- Treat returned node, edge, round, and event IDs as canonical persisted data.
- Map behavior from `error.code`, not from human-readable `error.message`.
- Keep `anonymousClientId` client-side; it must never appear in graph or
  Realtime payloads.
- Display the edge direction as `selected source depends on new target`.
- Reject an unsupported response contract version and show the generic
  unavailable/retry state.

## Ordered tasks

1. Build the mobile-first page with a short explanation and visible simulated
   data disclaimer.
2. Load the graph snapshot and transform active nodes into searchable source
   options.
3. Generate an anonymous UUID once and store it under a versioned local-storage
   key. Reuse it across reloads and rounds.
4. After client mount, calculate `Math.random() < 0.5` for the current render.
   Always show Europe; show external/unknown choices only for the enabled
   variant. Avoid server/client hydration differences.
5. Implement fields for source, target company name, organization type, and
   jurisdiction with the canonical shared Zod validation.
6. Prevent double-click submission and keep entered data for recoverable network
   errors.
7. Map API statuses to concise states: invalid, duplicate, already contributed,
   paused, capacity reached, unavailable, and retryable network error.
8. Replace the form with a success screen after `201`; do not allow another
   contribution until the session round changes.
9. Add loading, empty-graph, offline, and session-paused states.
10. Test keyboard use, labels, focus management, screen-reader status messages,
    and common 320-430px widths.

## Copy requirements

- Heading: `Add one hidden dependency`
- Instruction: `Choose an organization and add one company it depends on.`
- Direction helper: `[Selected organization] depends on [new company].`
- Disclaimer: `This is simulated, unverified demo data—not a factual claim.`
- Success: `Dependency added. Look at the main screen to see what the group
  uncovers.`

## Acceptance criteria

- A user can complete the happy path comfortably with one hand on a phone.
- The relationship direction is clear before submission.
- Search handles dozens of active nodes without a long native select list.
- The form variant is chosen only in the browser and does not cause hydration
  warnings.
- Company names over 60 characters and self-dependencies are blocked locally.
- API validation remains authoritative; client validation never invents a
  different rule.
- Submitted and parsed fixtures conform to contract version `1` without local
  adapters or alternate enum spellings.
- Reloading may reroll visible jurisdictions but cannot bypass a successful
  contribution in the current round.
- Paused and already-contributed users receive a stable explanatory screen.
- HTML-like audience text is displayed as plain text.
- Owned unit/component/end-to-end tests pass.

## Handoff

Provide screenshots at 320px and 430px, tested device/browser notes, test output,
known limitations, and commit SHA to the integration owner.
