# Transport amendment to the Sovereignty Lens data contract

Amends: [`data-contract.md`](data-contract.md)
Contract version: **`1` (unchanged)**
Status: accepted, supersedes the transport statements in `plan.md` and
`workstreams/*/plan.md`

This amendment records a change of **transport and hosting only**. No wire shape,
field name, enum value, error code, or status mapping changes. Nothing here
increments the contract version.

## What changed

| Concern | Before | Now |
| --- | --- | --- |
| Live delivery | Supabase Realtime Broadcast over WebSocket | Server-Sent Events served by the backend |
| Database | Supabase-hosted PostgreSQL | Self-hosted plain PostgreSQL |
| API runtime | Next.js route handlers on Vercel | Standalone Java 21 / Spring Boot 3 service on port `8080` |

Supabase is not used by this system in any role.

## What did not change

- Contract version stays `1`. Every HTTP body and every event still carries
  `contractVersion: 1`.
- All request, response, and event JSON shapes are byte-identical, including
  `DependencyCreatedEvent` and `GraphInvalidatedEvent`.
- All field names remain camelCase; all enum values are unchanged.
- `ApiErrorCode` values and the fixed error-code-to-HTTP-status mapping are
  unchanged (`400`, `401`, `403`, `404`, `409`, `423`, `429`, `500`).
- Edge direction still means `source organization depends on target
  organization`.
- Events remain an acceleration hint. `GraphSnapshot` from
  `GET /api/sessions/{slug}/graph` remains authoritative, and reconciliation
  rules are unchanged.

## The SSE endpoint

`GET /api/sessions/{slug}/events` responds with `Content-Type: text/event-stream`.

Each message is framed as:

```
id: 9b7d4f21-3c8e-4d6a-b5f0-7e2c1a8d9034
event: dependency.created
data: {"contractVersion":1,"event":"dependency.created", ...}

```

- The SSE `event:` field is the graph event name: `dependency.created` or
  `graph.invalidated`. It duplicates the `event` field inside the JSON object.
- The SSE `data:` field is the canonical JSON object, serialized on one line.
- The SSE `id:` field equals the event's `eventId`, so a browser automatically
  resumes with a `Last-Event-ID` request header after a dropped connection.
- The server writes a comment heartbeat line `: ping` every 15 seconds to keep
  proxies and idle-timeout middleboxes from closing the stream.

## Topic name preserved as a field-level concept

The logical topic `sovereignty:<sessionSlug>:round:<round>` is retained as a
naming and filtering concept, not as a transport channel. The stream is
per-session, not per-round, so consumers **must still discard any event whose
`sessionSlug` or `round` does not match what they are currently displaying**.
`GraphEvent.topicFor(sessionSlug, round)` in the Java contract produces the same
string as before.

## Migration note for the presentation workstream

1. Replace the Supabase channel subscription with
   `new EventSource('<API_BASE>/api/sessions/demo/events')`.
2. Register listeners per event name:
   `es.addEventListener('dependency.created', ...)` and
   `es.addEventListener('graph.invalidated', ...)`; `JSON.parse(e.data)` and
   validate with the existing `GraphEvent` schema.
3. Keep the existing idempotency-by-`eventId` bounded set and the ID-based
   node/edge deduplication exactly as designed.
4. Keep debounced snapshot reconciliation after every applied event, and a full
   snapshot fetch on load, round change, reconnect, invalidation, malformed
   payload, or sequence uncertainty.
5. Keep the 3-second polling fallback, now gated on
   `es.readyState !== EventSource.OPEN` instead of the Supabase subscription
   state. Stop polling once the stream is `OPEN` and one reconciliation has
   succeeded.
6. Reconnection is handled by the browser; no manual resubscribe on round reset
   is required, but the client still refetches the snapshot and updates the
   round it filters on.

## Row-level security

RLS is no longer relevant to this design. Browsers never connect to PostgreSQL.
The backend is the only database client and connects with a least-privilege
application role that has `SELECT`, `INSERT`, and `UPDATE` on the three
application tables and nothing else. Authorization is enforced in the service
layer: the audience endpoint is public and rate-limited by contract invariants,
and every `/api/admin/**` endpoint requires the signed `sl_admin` cookie.
