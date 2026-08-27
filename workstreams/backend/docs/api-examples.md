# API examples

Copy-pasteable `curl` calls for every endpoint of the Sovereignty Lens backend.

- Base URL: `http://localhost:8080`
- Session slug: `demo`
- Canonical schemas: [`../openapi/openapi.yaml`](../openapi/openapi.yaml)
- Wire contract: [`../../../contracts/data-contract.md`](../../../contracts/data-contract.md)
  and [`../../../contracts/transport-amendment.md`](../../../contracts/transport-amendment.md)

Every body carries `contractVersion: 1`. All example organizations are fictional;
all data served by this API is simulated, unverified demo data.

> On Windows PowerShell, `curl` is an alias for `Invoke-WebRequest`. Use
> `curl.exe` explicitly, or run these from Git Bash / WSL.

Optional convenience:

```bash
export API=http://localhost:8080
```

## Health

```bash
curl -sS "$API/api/health"
```

```json
{ "status": "ok", "version": "1.0.0", "time": "2026-08-27T10:15:30Z" }
```

## Public graph snapshot

The authoritative read. Use it at load, after any `graph.invalidated` event,
after a reconnect, and as the 3-second polling fallback.

```bash
curl -sS "$API/api/sessions/demo/graph"
```

```json
{
  "contractVersion": 1,
  "session": {
    "id": "6f1b3c2a-9d54-4a7e-8b21-2f0c5d7e9a10",
    "slug": "demo",
    "title": "European Digital Services Agency dependency demo",
    "status": "open",
    "currentRound": 3,
    "rootOrganizationId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  },
  "nodes": [
    {
      "id": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      "name": "European Digital Services Agency",
      "organizationType": "government",
      "jurisdiction": "europe",
      "isSeed": true
    },
    {
      "id": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
      "name": "Baltic Data Works",
      "organizationType": "cloud",
      "jurisdiction": "europe",
      "isSeed": true
    }
  ],
  "edges": [
    {
      "id": "5e6f7081-92a3-4ebf-80c1-4c5d6e7f8091",
      "sourceOrganizationId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      "targetOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
      "isSeed": true,
      "status": "active",
      "createdAt": "2026-08-27T09:00:00Z"
    }
  ],
  "serverTime": "2026-08-27T10:15:31Z"
}
```

Unknown slug (`404`):

```bash
curl -sS -i "$API/api/sessions/no-such-session/graph"
```

## Submit a dependency

Happy path (`201`). `anonymousClientId` is the UUID the browser persists in local
storage; the server hashes it before storage and never returns it.

```bash
curl -sS -i -X POST "$API/api/sessions/demo/dependencies" \
  -H 'Content-Type: application/json' \
  -d '{
    "contractVersion": 1,
    "anonymousClientId": "b1c9f0d2-7a44-4e6b-9c31-5d0e8f2a6b73",
    "sourceOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
    "target": {
      "name": "Northwind Cloud Services",
      "organizationType": "cloud",
      "jurisdiction": "united_states"
    }
  }'
```

```json
{
  "contractVersion": 1,
  "eventId": "9b7d4f21-3c8e-4d6a-b5f0-7e2c1a8d9034",
  "round": 3,
  "node": {
    "id": "8a9b0c1d-2e3f-4a5b-8c7d-6e5f4a3b2c1d",
    "name": "Northwind Cloud Services",
    "organizationType": "cloud",
    "jurisdiction": "united_states",
    "isSeed": false
  },
  "edge": {
    "id": "7f8e9d0c-1b2a-4c3d-8e5f-6a7b8c9d0e1f",
    "sourceOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
    "targetOrganizationId": "8a9b0c1d-2e3f-4a5b-8c7d-6e5f4a3b2c1d",
    "isSeed": false,
    "status": "active",
    "createdAt": "2026-08-27T10:15:30Z"
  }
}
```

### Representative error: same browser contributes twice in one round (`409`)

Repeat the exact call above with the same `anonymousClientId`:

```bash
curl -sS -i -X POST "$API/api/sessions/demo/dependencies" \
  -H 'Content-Type: application/json' \
  -d '{
    "contractVersion": 1,
    "anonymousClientId": "b1c9f0d2-7a44-4e6b-9c31-5d0e8f2a6b73",
    "sourceOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
    "target": {
      "name": "Meridian Handset Works",
      "organizationType": "hardware",
      "jurisdiction": "china"
    }
  }'
```

```
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "contractVersion": 1,
  "error": {
    "code": "ALREADY_CONTRIBUTED",
    "message": "This device already added a dependency in the current round.",
    "retryable": false
  }
}
```

Other errors from this endpoint use the same envelope: `400 VALIDATION_ERROR`
(bad name length, self-dependency, `government` target), `404
SESSION_NOT_FOUND` / `SOURCE_NOT_FOUND`, `409 DUPLICATE_DEPENDENCY`, `423
SESSION_PAUSED`, `429 ROUND_CAPACITY_REACHED`, `500 INTERNAL_ERROR`. A quick
`400`:

```bash
curl -sS -i -X POST "$API/api/sessions/demo/dependencies" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"anonymousClientId":"b1c9f0d2-7a44-4e6b-9c31-5d0e8f2a6b73","sourceOrganizationId":"3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f","target":{"name":"X","organizationType":"cloud","jurisdiction":"europe"}}'
```

## Live event stream (SSE)

`-N` disables buffering so frames print as they arrive. The stream stays open;
stop it with `Ctrl-C`.

```bash
curl -sS -N -H 'Accept: text/event-stream' "$API/api/sessions/demo/events"
```

```
: ping

id: 9b7d4f21-3c8e-4d6a-b5f0-7e2c1a8d9034
event: dependency.created
data: {"contractVersion":1,"event":"dependency.created","eventId":"9b7d4f21-3c8e-4d6a-b5f0-7e2c1a8d9034","sessionSlug":"demo","round":3,"node":{...},"edge":{...},"occurredAt":"2026-08-27T10:15:30Z"}

id: c4e2a180-5b93-4f07-9a6d-1d8b3f5e7c20
event: graph.invalidated
data: {"contractVersion":1,"event":"graph.invalidated","eventId":"c4e2a180-5b93-4f07-9a6d-1d8b3f5e7c20","sessionSlug":"demo","round":3,"reason":"hide","occurredAt":"2026-08-27T10:16:02Z"}
```

Resume after a drop, exactly as a browser would:

```bash
curl -sS -N \
  -H 'Accept: text/event-stream' \
  -H 'Last-Event-ID: 9b7d4f21-3c8e-4d6a-b5f0-7e2c1a8d9034' \
  "$API/api/sessions/demo/events"
```

Open a stream in one terminal and POST a contribution in another to watch the
`dependency.created` frame appear.

## QR code

The image encodes `APP_PUBLIC_BASE_URL` plus the route, so a phone that scans the
projected code lands on the form.

```bash
# Contribution QR as PNG (defaults: target=contribute, format=png)
curl -sS "$API/api/qr" -o contribute-qr.png

# Presentation QR as SVG
curl -sS "$API/api/qr?target=present&format=svg" -o present-qr.svg
```

## Admin flow with a cookie jar

`-c cookies.txt` writes the `sl_admin` cookie on login; `-b cookies.txt` sends it
on every later call. The cookie is signed, `HttpOnly` and `SameSite=Lax`.

### Login

```bash
curl -sS -i -c cookies.txt -X POST "$API/api/admin/login" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"password":"correct-horse-battery-staple"}'
```

```
HTTP/1.1 200 OK
Set-Cookie: sl_admin=...; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800
```

```json
{ "contractVersion": 1, "authenticated": true }
```

Wrong password (`401`, deliberately generic):

```bash
curl -sS -i -X POST "$API/api/admin/login" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"password":"wrong"}'
```

```json
{
  "contractVersion": 1,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Presenter authentication required.",
    "retryable": false
  }
}
```

### Presenter actions

```bash
# Pause contributions
curl -sS -b cookies.txt -X POST "$API/api/admin/sessions/demo/actions" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"action":{"type":"pause"}}'

# Resume
curl -sS -b cookies.txt -X POST "$API/api/admin/sessions/demo/actions" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"action":{"type":"resume"}}'

# Undo the newest active audience dependency
curl -sS -b cookies.txt -X POST "$API/api/admin/sessions/demo/actions" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"action":{"type":"undo"}}'

# Start a new round (increments currentRound, keeps seed data)
curl -sS -b cookies.txt -X POST "$API/api/admin/sessions/demo/actions" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"action":{"type":"reset"}}'
```

```json
{
  "contractVersion": 1,
  "eventId": "c4e2a180-5b93-4f07-9a6d-1d8b3f5e7c20",
  "session": {
    "id": "6f1b3c2a-9d54-4a7e-8b21-2f0c5d7e9a10",
    "slug": "demo",
    "title": "European Digital Services Agency dependency demo",
    "status": "paused",
    "currentRound": 3,
    "rootOrganizationId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  }
}
```

Without the cookie jar the same call returns `401 UNAUTHORIZED`:

```bash
curl -sS -i -X POST "$API/api/admin/sessions/demo/actions" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"action":{"type":"pause"}}'
```

### Moderation list

All current-round, non-seed dependencies including hidden ones, newest first.

```bash
curl -sS -b cookies.txt "$API/api/admin/sessions/demo/dependencies"
```

```json
{
  "contractVersion": 1,
  "session": {
    "id": "6f1b3c2a-9d54-4a7e-8b21-2f0c5d7e9a10",
    "slug": "demo",
    "title": "European Digital Services Agency dependency demo",
    "status": "open",
    "currentRound": 3,
    "rootOrganizationId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
  },
  "dependencies": [
    {
      "edge": {
        "id": "7f8e9d0c-1b2a-4c3d-8e5f-6a7b8c9d0e1f",
        "sourceOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
        "targetOrganizationId": "8a9b0c1d-2e3f-4a5b-8c7d-6e5f4a3b2c1d",
        "isSeed": false,
        "status": "active",
        "createdAt": "2026-08-27T10:15:30Z"
      },
      "source": {
        "id": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
        "name": "Baltic Data Works",
        "organizationType": "cloud",
        "jurisdiction": "europe",
        "isSeed": true
      },
      "target": {
        "id": "8a9b0c1d-2e3f-4a5b-8c7d-6e5f4a3b2c1d",
        "name": "Northwind Cloud Services",
        "organizationType": "cloud",
        "jurisdiction": "united_states",
        "isSeed": false
      }
    }
  ]
}
```

### Hide and restore one dependency

```bash
# Hide
curl -sS -b cookies.txt -X PATCH \
  "$API/api/admin/dependencies/7f8e9d0c-1b2a-4c3d-8e5f-6a7b8c9d0e1f" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"status":"hidden"}'

# Restore
curl -sS -b cookies.txt -X PATCH \
  "$API/api/admin/dependencies/7f8e9d0c-1b2a-4c3d-8e5f-6a7b8c9d0e1f" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"status":"active"}'
```

```json
{
  "contractVersion": 1,
  "eventId": "d5f3b291-6ca4-4018-8b7e-2e9c4a6f8d31",
  "edge": {
    "id": "7f8e9d0c-1b2a-4c3d-8e5f-6a7b8c9d0e1f",
    "sourceOrganizationId": "3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f",
    "targetOrganizationId": "8a9b0c1d-2e3f-4a5b-8c7d-6e5f4a3b2c1d",
    "isSeed": false,
    "status": "hidden",
    "createdAt": "2026-08-27T10:15:30Z"
  }
}
```

A seed edge, or an edge from an earlier round, returns `404 NOT_FOUND`.

### Logout

Always `204`, with or without a valid session.

```bash
curl -sS -i -b cookies.txt -c cookies.txt -X POST "$API/api/admin/logout"
rm -f cookies.txt
```

## End-to-end smoke sequence

```bash
export API=http://localhost:8080
curl -sS "$API/api/health"
curl -sS "$API/api/sessions/demo/graph" | head -c 400; echo
curl -sS -N "$API/api/sessions/demo/events" &   # watch in the background
curl -sS -X POST "$API/api/sessions/demo/dependencies" \
  -H 'Content-Type: application/json' \
  -d '{"contractVersion":1,"anonymousClientId":"'"$(uuidgen | tr 'A-Z' 'a-z')"'","sourceOrganizationId":"3c4d5e6f-7081-4c9d-8eaf-2a3b4c5d6e7f","target":{"name":"Northwind Cloud Services","organizationType":"cloud","jurisdiction":"united_states"}}'
kill %1
```
