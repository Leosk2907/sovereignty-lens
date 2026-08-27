# Sovereignty Lens

An interactive, audience-built visualization of hidden dependencies affecting
European digital sovereignty.

## Local development

```bash
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). Without Supabase variables,
the app uses its browser-based demo transport. The mock admin password is
`demo`.

## Routes

- `/` — live presentation graph
- `/contribute` — mobile audience contribution flow
- `/about` — project explanation and prototype disclosure
- `/admin` — password-protected live graph and presenter controls
- `/present` — compatibility redirect to `/`

## Commands

```bash
npm run lint
npm run typecheck
npm test
npm run test:e2e
npm run build
```

Copy `.env.example` to `.env.local` to connect the frontend to the production
API and Supabase Realtime. Never commit real credentials.

See [plan.md](./plan.md) for the delivery plan and
[contracts/data-contract.md](./contracts/data-contract.md) for the canonical
wire contract.
