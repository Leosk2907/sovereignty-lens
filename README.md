# Sovereignty Lens

An interactive, audience-built visualization of hidden dependencies affecting
European digital sovereignty.

## Local development

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). Without an API base URL,
the app uses its browser-based demo transport. The mock admin password is
`demo`.

To run against the persistent Spring/PostgreSQL backend, start the stack in
`workstreams/backend`, then set:

```text
NEXT_PUBLIC_USE_MOCK_API=false
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

## Routes

- `/`: live presentation graph
- `/contribute`: mobile audience contribution flow
- `/about`: project explanation and prototype disclosure
- `/admin`: password-protected live graph and presenter controls
- `/present`: compatibility redirect to `/`

## Commands

```bash
npm run lint
npm run typecheck
npm test
npm run test:e2e
npm run build
```

Copy `.env.example` to `.env.local` to connect the frontend to the backend HTTP
API and Server-Sent Events stream. Never commit real credentials.

See [plan.md](./plan.md) for the delivery plan and
[contracts/data-contract.md](./contracts/data-contract.md) for the canonical
wire contract.
