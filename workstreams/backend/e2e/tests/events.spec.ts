import { test, expect } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import {
  CONTRACT_VERSION,
  SEED,
  SESSION_SLUG,
  adminAction,
  adminLogin,
  apiUrl,
  contributeOk,
  expectApiError,
  findEvent,
  getSnapshot,
  newAnonymousContext,
  newClientId,
  paths,
  readSseEvents,
  resetRound,
  setDependencyStatus,
  uniqueCompanyName,
} from './helpers';
import type { ContributionResult } from './helpers';

/**
 * The live event stream, `GET /api/sessions/demo/events`.
 *
 * Playwright's `APIRequestContext` buffers a whole response body before
 * returning it, which never completes for a long-lived `text/event-stream`, so
 * these tests consume the stream through `readSseEvents`, which uses Node's
 * `fetch` and iterates the body. The action that produces the event runs from
 * the `onOpen` callback, so the stream is already listening and cannot miss it.
 */
test.describe('server-sent events', () => {
  let admin: APIRequestContext;
  let cookie: string;

  test.beforeAll(async () => {
    admin = await newAnonymousContext();
    cookie = await adminLogin(admin);
  });

  test.afterAll(async () => {
    await admin.dispose();
  });

  test.beforeEach(async ({ request }) => {
    await resetRound(request, cookie);
  });

  test('a contribution delivers dependency.created matching the HTTP 201 body exactly', async ({
    request,
  }) => {
    let created: ContributionResult | undefined;

    const events = await readSseEvents(apiUrl(paths.events()), {
      count: 1,
      timeoutMs: 20_000,
      onOpen: async () => {
        created = await contributeOk(request, {
          clientId: newClientId(),
          sourceId: SEED.balticDataWorks,
          name: uniqueCompanyName('Streamco'),
          type: 'cloud',
          jurisdiction: 'united_states',
        });
      },
    });

    expect(created, 'the contribution never completed').toBeTruthy();
    const frame = findEvent(events, 'dependency.created');
    expect(frame, `no dependency.created frame arrived; got ${JSON.stringify(events)}`).toBeTruthy();

    const payload = frame!.json;
    expect(payload, `data: was not valid JSON: ${frame!.data}`).toBeTruthy();

    // Envelope
    expect(payload.contractVersion).toBe(CONTRACT_VERSION);
    expect(payload.event).toBe('dependency.created');
    expect(payload.sessionSlug).toBe(SESSION_SLUG);
    expect(payload.round).toBe(created!.round);
    expect(Number.isNaN(Date.parse(payload.occurredAt))).toBe(false);

    // The three identifiers a client deduplicates on.
    expect(payload.eventId).toBe(created!.eventId);
    expect(payload.node.id).toBe(created!.node.id);
    expect(payload.edge.id).toBe(created!.edge.id);

    // The canonical records are identical, not merely consistent.
    expect(payload.node).toEqual(created!.node);
    expect(payload.edge).toEqual(created!.edge);

    // The anonymous client id is never broadcast.
    expect(frame!.data).not.toContain('anonymousClientId');

    // The SSE id: field carries the eventId so a browser can resume with
    // Last-Event-ID.
    expect(frame!.id).toBe(created!.eventId);
  });

  test('pause and resume deliver graph.invalidated with the matching reason', async ({
    request,
  }) => {
    const events = await readSseEvents(apiUrl(paths.events()), {
      count: 2,
      timeoutMs: 20_000,
      onOpen: async () => {
        expect((await adminAction(request, 'pause', cookie)).status()).toBe(200);
        expect((await adminAction(request, 'resume', cookie)).status()).toBe(200);
      },
    });

    expect(events.length, `expected two frames, got ${JSON.stringify(events)}`).toBe(2);

    const [paused, resumed] = events;
    expect(paused.event).toBe('graph.invalidated');
    expect(paused.json.event).toBe('graph.invalidated');
    expect(paused.json.reason).toBe('pause');
    expect(paused.json.sessionSlug).toBe(SESSION_SLUG);
    expect(paused.json.contractVersion).toBe(CONTRACT_VERSION);
    // graph.invalidated carries no graph records; the client refetches.
    expect(paused.json.node).toBeUndefined();
    expect(paused.json.edge).toBeUndefined();

    expect(resumed.event).toBe('graph.invalidated');
    expect(resumed.json.reason).toBe('resume');

    // Every frame's SSE id: field equals its eventId.
    for (const frame of events) {
      expect(frame.id).toBe(frame.json.eventId);
    }
  });

  test('hiding a dependency delivers graph.invalidated with reason hide', async ({ request }) => {
    const created = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Hidestreamco'),
    });

    const events = await readSseEvents(apiUrl(paths.events()), {
      count: 1,
      timeoutMs: 20_000,
      onOpen: async () => {
        const response = await setDependencyStatus(request, cookie, created.edge.id, 'hidden');
        expect(response.status()).toBe(200);
      },
    });

    const frame = findEvent(events, 'graph.invalidated');
    expect(frame, `no graph.invalidated frame arrived; got ${JSON.stringify(events)}`).toBeTruthy();
    expect(frame!.json.reason).toBe('hide');
    expect(frame!.id).toBe(frame!.json.eventId);
    expect(frame!.json.round).toBe((await getSnapshot(request)).session.currentRound);
  });

  test('reset delivers graph.invalidated carrying the new current round', async ({ request }) => {
    const before = await getSnapshot(request);

    const events = await readSseEvents(apiUrl(paths.events()), {
      count: 1,
      timeoutMs: 20_000,
      onOpen: async () => {
        expect((await adminAction(request, 'reset', cookie)).status()).toBe(200);
      },
    });

    const frame = findEvent(events, 'graph.invalidated');
    expect(frame, `no graph.invalidated frame arrived; got ${JSON.stringify(events)}`).toBeTruthy();
    expect(frame!.json.reason).toBe('reset');
    // A reset event already uses the NEW round.
    expect(frame!.json.round).toBe(before.session.currentRound + 1);
    expect(frame!.id).toBe(frame!.json.eventId);
  });

  test('the SSE id field equals the eventId for every kind of event', async ({ request }) => {
    const events = await readSseEvents(apiUrl(paths.events()), {
      count: 2,
      timeoutMs: 20_000,
      onOpen: async () => {
        await contributeOk(request, {
          clientId: newClientId(),
          sourceId: SEED.rhinePublicNetworks,
          name: uniqueCompanyName('Idco'),
        });
        expect((await adminAction(request, 'undo', cookie)).status()).toBe(200);
      },
    });

    expect(events.length).toBe(2);
    expect(events.map((frame) => frame.event)).toEqual([
      'dependency.created',
      'graph.invalidated',
    ]);

    for (const frame of events) {
      expect(frame.json, `data: was not valid JSON: ${frame.data}`).toBeTruthy();
      expect(frame.id).toBe(frame.json.eventId);
      // The SSE event: field duplicates the `event` field inside the payload.
      expect(frame.event).toBe(frame.json.event);
    }
  });

  test('the event stream of an unknown slug returns 404 SESSION_NOT_FOUND', async ({
    request,
  }) => {
    // A 404 is a short, finite body, so the buffering request fixture is fine.
    const response = await request.get(paths.events('no-such-session'), {
      headers: { Accept: 'text/event-stream' },
    });
    await expectApiError(response, 404, 'SESSION_NOT_FOUND');
  });
});
