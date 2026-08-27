# Presenter administration and quality workstream plan

Branch: `feature/admin-quality`

## Outcome

Deliver the presenter recovery interface, supporting explanation, full-system
end-to-end coverage, accessibility pass, and a repeatable demo rehearsal.

## Owned areas

- `/admin` login and control interface
- `/about` explanation and dependency disclosure
- Admin API client and presenter confirmation/error states
- Cross-feature Playwright scenarios
- Accessibility, responsive, and rehearsal checklists
- README setup/deployment/demo-runbook updates after integration

Do not change database, route contracts, graph algorithms, or audience form
logic. File contract issues with the integration owner instead.

## Data contract obligations

[`../../contracts/data-contract.md`](../../contracts/data-contract.md) is
authoritative. This workstream produces admin request shapes and consumes admin
results, dependency lists, graph snapshots, and structured API errors.

- Import shared types and Zod schemas; do not define local admin payload types.
- Send `AdminLoginRequest`, `AdminActionRequest`, and
  `DependencyStatusRequest` with `contractVersion: 1`.
- Render `AdminDependencyList` newest first and identify entries by `edge.id`.
- Use `SessionSummary.status` and `.currentRound` as authoritative presenter
  state.
- Map failures from `ApiErrorResponse.error.code`; never branch on message text.
- Treat returned event IDs as operation identifiers for deduplication and status
  feedback.
- Preserve canonical node/edge IDs and direction in admin labels and test
  assertions.
- Reject unsupported versions and show a non-destructive refresh/retry state.

## Ordered tasks

1. Build a password login page without exposing configuration details in errors.
2. Build an authenticated control page showing session status, round, active
   submission count, and connection health.
3. Implement pause/resume with immediate visible state.
4. Load `GET /api/admin/sessions/demo/dependencies`, list current-round audience
   dependencies newest first, and implement hide/restore.
5. Implement undo latest and make a no-op state clear when nothing is available.
6. Implement reset with an explicit confirmation describing that seed data stays
   and audience data from the old round disappears from the public graph.
7. Add copy/open controls for presentation and contribution URLs.
8. Build `/about` with direct/transitive dependency explanation, production-data
   caveats, simulated-data warning, and Vercel/Supabase disclosure.
9. Implement the cross-feature Playwright scenarios from the root plan using
   isolated test rounds or a local test database.
10. Run keyboard and automated accessibility checks on all four public routes.
11. Document setup, environment, migrations, preview/production deploy, reset,
    troubleshooting, and the final demo sequence.
12. Conduct and record the final rehearsal results after all merges.

## Presenter runbook behavior

- Before the pitch: verify production health, open `/present`, open `/admin` on
  a separate device, reset, ensure status is open, and test one phone.
- During the pitch: keep admin available, pause if abuse occurs, hide the entry,
  then resume.
- If live updates fail: refresh `/present`; authoritative data remains in
  Supabase.
- If the graph becomes unreadable: undo recent entries or reset only if the
  presenter explicitly accepts clearing the current audience round.
- After rehearsal: reset again before the real presentation.

## Acceptance criteria

- Invalid login does not reveal secret or implementation details.
- All state-changing controls prevent repeat clicks and report success/failure.
- All admin requests, success responses, lists, and error fixtures parse through
  the canonical version `1` schemas.
- Reset requires confirmation; hide/restore and pause/resume do not.
- Presenter actions update the presentation without a manual refresh.
- The about page clearly distinguishes prototype demonstration from factual
  dependency research.
- Full end-to-end flows cover European submission, external reveal, duplicate
  rejection, pause/resume, hide/restore, undo, reset, refresh recovery, and
  untrusted text.
- README instructions work from a clean clone.
- The production QR code is tested on iOS and Android.
- The final 10-device rehearsal and 50-client simulation are recorded as passed
  or include explicit mitigations.

## Handoff

Provide Playwright output, accessibility results, final README/runbook changes,
rehearsal results, known limitations, and commit SHA to the integration owner.
