import { test, expect } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import {
  CONTRACT_VERSION,
  SEED,
  SEED_EDGE_IDS,
  adminAction,
  adminLogin,
  contribute,
  contributeOk,
  expectApiError,
  getSnapshot,
  listAdminDependencies,
  newAnonymousContext,
  newClientId,
  paths,
  resetRound,
  setDependencyStatus,
  uniqueCompanyName,
  withAdmin,
} from './helpers';

/**
 * Presenter controls under `/api/admin/**`.
 *
 * The `sl_admin` cookie is held explicitly instead of relying on a context's
 * cookie jar, so an authenticated and an unauthenticated identity can coexist
 * inside one test — the 401 assertions depend on that.
 *
 * The login runs on its own API context rather than on the `request` fixture,
 * because test-scoped fixtures are not available inside `beforeAll`.
 */
test.describe('presenter administration', () => {
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

  test.describe('authentication', () => {
    test('a wrong password returns exactly the same 401 body as an unauthenticated call', async () => {
      // Guaranteed cookie-free context: the point of this test is that nothing
      // distinguishes "bad password" from "no session".
      const anon = await newAnonymousContext();
      try {
        const wrongPassword = await anon.post(paths.adminLogin, {
          data: {
            contractVersion: CONTRACT_VERSION,
            password: 'definitely-not-the-presenter-password',
          },
        });
        const noCookie = await anon.get(paths.adminDependencies());

        const wrongPasswordBody = await expectApiError(wrongPassword, 401, 'UNAUTHORIZED');
        const noCookieBody = await expectApiError(noCookie, 401, 'UNAUTHORIZED');

        // Never reveal which check failed: the same envelope, field for field.
        expect(wrongPasswordBody).toEqual(noCookieBody);
      } finally {
        await anon.dispose();
      }
    });

    test('admin endpoints reject a caller with no cookie', async () => {
      const anon = await newAnonymousContext();
      try {
        await expectApiError(
          await anon.post(paths.adminActions(), {
            data: { contractVersion: CONTRACT_VERSION, action: { type: 'pause' } },
          }),
          401,
          'UNAUTHORIZED',
        );

        await expectApiError(await anon.get(paths.adminDependencies()), 401, 'UNAUTHORIZED');

        await expectApiError(
          await anon.patch(paths.adminDependency(SEED.edgeRootToAlpine), {
            data: { contractVersion: CONTRACT_VERSION, status: 'hidden' },
          }),
          401,
          'UNAUTHORIZED',
        );
      } finally {
        await anon.dispose();
      }
    });

    test('a badly signed cookie is rejected like a missing one', async () => {
      const anon = await newAnonymousContext();
      try {
        const response = await anon.get(paths.adminDependencies(), {
          headers: { Cookie: 'sl_admin=not-a-validly-signed-value' },
        });
        await expectApiError(response, 401, 'UNAUTHORIZED');
      } finally {
        await anon.dispose();
      }
    });

    test('logout succeeds with or without a session', async () => {
      const anon = await newAnonymousContext();
      try {
        // Called defensively by the admin UI, so it must not fail unauthenticated.
        expect((await anon.post(paths.adminLogout)).status()).toBe(204);

        const scopedCookie = await adminLogin(anon);
        expect(
          (await anon.get(paths.adminDependencies(), withAdmin(scopedCookie))).status(),
        ).toBe(200);
        expect((await anon.post(paths.adminLogout, withAdmin(scopedCookie))).status()).toBe(204);
      } finally {
        await anon.dispose();
      }
    });
  });

  test('pause blocks contributions with 423, resume lets them through', async ({ request }) => {
    const paused = await adminAction(request, 'pause', cookie);
    expect(paused.status()).toBe(200);
    expect((await paused.json()).session.status).toBe('paused');

    const blocked = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Pausedco'),
    });
    await expectApiError(blocked, 423, 'SESSION_PAUSED');

    const resumed = await adminAction(request, 'resume', cookie);
    expect(resumed.status()).toBe(200);
    expect((await resumed.json()).session.status).toBe('open');

    const accepted = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Resumedco'),
    });
    expect(accepted.status()).toBe(201);
  });

  test('hiding a dependency removes it from the public snapshot but not from the admin list', async ({
    request,
  }) => {
    const created = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Hideco'),
      type: 'cloud',
      jurisdiction: 'united_states',
    });

    expect(edgeIds(await getSnapshot(request))).toContain(created.edge.id);

    const hidden = await setDependencyStatus(request, cookie, created.edge.id, 'hidden');
    expect(hidden.status()).toBe(200);
    const hiddenBody = await hidden.json();
    expect(hiddenBody.contractVersion).toBe(CONTRACT_VERSION);
    expect(hiddenBody.edge.id).toBe(created.edge.id);
    expect(hiddenBody.edge.status).toBe('hidden');

    const afterHide = await getSnapshot(request);
    expect(edgeIds(afterHide)).not.toContain(created.edge.id);
    // The target is reachable only through that edge, so its node goes too.
    expect(nodeIds(afterHide)).not.toContain(created.node.id);
    // Moderation never touches seed data.
    for (const seedEdge of SEED_EDGE_IDS) {
      expect(edgeIds(afterHide)).toContain(seedEdge);
    }

    // Still visible to the presenter, flagged as hidden.
    const list = await listAdminDependencies(request, cookie);
    const entry = list.dependencies.find((item) => item.edge.id === created.edge.id);
    expect(entry, 'hidden dependency missing from the admin list').toBeTruthy();
    expect(entry!.edge.status).toBe('hidden');
    expect(entry!.source.id).toBe(SEED.balticDataWorks);
    expect(entry!.target.id).toBe(created.node.id);

    const restored = await setDependencyStatus(request, cookie, created.edge.id, 'active');
    expect(restored.status()).toBe(200);
    expect((await restored.json()).edge.status).toBe('active');

    const afterRestore = await getSnapshot(request);
    expect(edgeIds(afterRestore)).toContain(created.edge.id);
    expect(nodeIds(afterRestore)).toContain(created.node.id);
  });

  test('a seed dependency cannot be hidden', async ({ request }) => {
    const response = await setDependencyStatus(request, cookie, SEED.edgeRootToAlpine, 'hidden');
    await expectApiError(response, 404, 'NOT_FOUND');
  });

  test('undo hides the most recent audience dependency', async ({ request }) => {
    const older = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Olderco'),
    });
    const newest = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.rhinePublicNetworks,
      name: uniqueCompanyName('Newestco'),
    });

    const response = await adminAction(request, 'undo', cookie);
    expect(response.status()).toBe(200);

    const snapshot = await getSnapshot(request);
    expect(edgeIds(snapshot)).not.toContain(newest.edge.id);
    expect(edgeIds(snapshot)).toContain(older.edge.id);

    // Undo hides, it does not delete: the record stays in the admin list.
    const list = await listAdminDependencies(request, cookie);
    expect(list.dependencies.find((item) => item.edge.id === newest.edge.id)?.edge.status).toBe(
      'hidden',
    );
    expect(list.dependencies.find((item) => item.edge.id === older.edge.id)?.edge.status).toBe(
      'active',
    );
  });

  test('the admin dependency list is ordered newest first', async ({ request }) => {
    const created = [];
    for (const source of [
      SEED.balticDataWorks,
      SEED.rhinePublicNetworks,
      SEED.alpineCivicSystems,
    ]) {
      created.push(
        await contributeOk(request, {
          clientId: newClientId(),
          sourceId: source,
          name: uniqueCompanyName('Orderco'),
        }),
      );
    }

    const list = await listAdminDependencies(request, cookie);
    expect(list.contractVersion).toBe(CONTRACT_VERSION);
    expect(list.session.slug).toBe('demo');

    const returnedIds = list.dependencies.map((item) => item.edge.id);
    const createdIds = created.map((item) => item.edge.id);
    // Newest first is the exact reverse of creation order.
    expect(returnedIds).toEqual([...createdIds].reverse());

    const times = list.dependencies.map((item) => Date.parse(item.edge.createdAt));
    for (let i = 1; i < times.length; i += 1) {
      expect(times[i - 1]).toBeGreaterThanOrEqual(times[i]);
    }

    // Audience-only: seed edges never appear in the moderation list.
    for (const item of list.dependencies) {
      expect(item.edge.isSeed).toBe(false);
    }
  });

  test('reset starts a new round, keeps seed data, drops old audience edges, and frees the browser', async ({
    request,
  }) => {
    const clientId = newClientId();
    const before = await getSnapshot(request);

    const contributed = await contributeOk(request, {
      clientId,
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Roundco'),
      type: 'cloud',
      jurisdiction: 'china',
    });
    expect(edgeIds(await getSnapshot(request))).toContain(contributed.edge.id);

    // The same browser is locked out for the rest of this round.
    await expectApiError(
      await contribute(request, {
        clientId,
        sourceId: SEED.rhinePublicNetworks,
        name: uniqueCompanyName('Blockedco'),
      }),
      409,
      'ALREADY_CONTRIBUTED',
    );

    const reset = await adminAction(request, 'reset', cookie);
    expect(reset.status()).toBe(200);
    const resetBody = await reset.json();
    expect(resetBody.session.currentRound).toBe(before.session.currentRound + 1);
    expect(resetBody.session.status).toBe('open');

    const after = await getSnapshot(request);
    expect(after.session.currentRound).toBe(before.session.currentRound + 1);

    // Audience edges from the previous round leave the public graph...
    expect(edgeIds(after)).not.toContain(contributed.edge.id);
    expect(nodeIds(after)).not.toContain(contributed.node.id);
    // ...and every seed edge survives the reset.
    for (const seedEdge of SEED_EDGE_IDS) {
      expect(edgeIds(after)).toContain(seedEdge);
    }
    expect(nodeIds(after)).toContain(SEED.root);

    // The moderation list is scoped to the new, empty round.
    const list = await listAdminDependencies(request, cookie);
    expect(list.session.currentRound).toBe(after.session.currentRound);
    expect(list.dependencies).toHaveLength(0);

    // The same browser may contribute again in the new round.
    const again = await contributeOk(request, {
      clientId,
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Nextroundco'),
    });
    expect(again.round).toBe(after.session.currentRound);
    expect(edgeIds(await getSnapshot(request))).toContain(again.edge.id);
  });

  test('an admin action on an unknown slug returns 404 SESSION_NOT_FOUND', async ({ request }) => {
    const response = await request.post(paths.adminActions('no-such-session'), {
      ...withAdmin(cookie),
      data: { contractVersion: CONTRACT_VERSION, action: { type: 'pause' } },
    });
    await expectApiError(response, 404, 'SESSION_NOT_FOUND');
  });

  test('an unknown admin action type returns 400 VALIDATION_ERROR', async ({ request }) => {
    const response = await request.post(paths.adminActions(), {
      ...withAdmin(cookie),
      data: { contractVersion: CONTRACT_VERSION, action: { type: 'detonate' } },
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });
});

function edgeIds(snapshot: { edges: { id: string }[] }): string[] {
  return snapshot.edges.map((edge) => edge.id);
}

function nodeIds(snapshot: { nodes: { id: string }[] }): string[] {
  return snapshot.nodes.map((node) => node.id);
}
