# Backend and foundation workstream plan

Branch: `feature/backend-foundation`

## Outcome

Deliver the shared application scaffold, reproducible Supabase schema, seed
data, secure route handlers, and deployable preview backend. Frontend owners
must be able to work against committed fixtures before the remote database is
ready.

## Owned areas

- Project/tooling configuration and environment validation
- Shared Zod schemas and TypeScript contracts
- Supabase client factories and generated database types
- Database migrations, seed migration, indexes, RLS, atomic submission, and
  database-backed Broadcast
- Public graph and contribution route handlers
- Admin authentication and action route handlers
- Backend unit/integration tests
- CI and first Vercel preview deployment

Do not implement the audience, Cytoscape, or admin page UI.

## Ordered tasks

1. Scaffold the Next.js TypeScript application with App Router, Tailwind,
   ESLint, Vitest, React Testing Library, and Playwright.
2. Add scripts for `dev`, `build`, `lint`, `typecheck`, `test`, `test:e2e`, and
   Supabase local lifecycle/migration commands.
3. Commit `.env.example` and a fail-fast server environment parser.
4. Implement the exact shared contracts from the root plan with Zod schemas.
5. Add deterministic fixture builders for one seeded graph and API errors.
6. Initialize Supabase and create the three tables, constraints, indexes,
   timestamp behavior, RLS policies, and Realtime Broadcast configuration.
7. Seed one `demo` session rooted at `European Digital Services Agency`, three
   fictional European suppliers, and connected seed dependencies.
8. Implement the transactional submission SQL function. In the same transaction,
   insert the dependency and call `realtime.send()` with a versioned canonical
   `dependency.created` payload on `sovereignty:demo:round:<round>`.
9. Emit `graph.invalidated` from admin mutation transactions for pause, resume,
   hide, restore, undo, and reset.
10. Implement graph loading with seed plus current-round active dependencies.
11. Implement all route handlers and map domain errors to the specified HTTP
    status codes.
12. Implement constant-time presenter-password comparison, signed cookie
    creation/verification, and server-only contributor hashing.
13. Add backend tests, including rollback behavior, and a GitHub Actions
    workflow.
14. Create Supabase/Vercel preview projects, apply migrations, configure secrets,
    and publish the preview URL for other workstreams.

## Seed scenario

Use only obviously fictional entities:

- European Digital Services Agency — government root, Europe
- Alpine Civic Systems — software, Europe
- Baltic Data Works — cloud, Europe
- Rhine Public Networks — telecom, Europe

Seed only European dependencies. The external reveal must come from audience
participation.

## Acceptance criteria

- A clean clone can install, validate, test, build, and start from documented
  commands.
- Migrations create a complete empty environment without dashboard-only steps.
- Reapplying migrations is safe.
- Fixtures conform to the same Zod schemas used by production handlers.
- Every endpoint returns a consistent JSON success/error envelope.
- Submission is atomic under concurrent requests.
- A committed submission produces exactly one versioned Broadcast event with
  identifiers matching the API response and stored rows.
- A rolled-back submission produces neither a stored dependency nor a live
  event.
- Anonymous database clients cannot insert, update, or delete.
- Service-role and presenter secrets never enter client bundles or logs.
- Preview deployment returns the seeded graph and accepts a valid dependency.
- All owned tests and CI checks pass before handoff.

## Handoff

Provide the preview URL, migration command, environment variable checklist,
fixture import path, sample requests/responses, known limitations, and commit SHA
to the integration owner.
