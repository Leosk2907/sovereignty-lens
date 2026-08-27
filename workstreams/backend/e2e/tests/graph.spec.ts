import { test, expect } from '@playwright/test';
import {
  CONTRACT_VERSION,
  SEED,
  SEED_EDGE_IDS,
  SEED_ORGANIZATION_IDS,
  SEED_ORGANIZATION_NAMES,
  SESSION_SLUG,
  expectApiError,
  getSnapshot,
  newAnonymousContext,
  paths,
  resetRound,
} from './helpers';

/**
 * Snapshot shape and invariants of `GET /api/sessions/{slug}/graph`.
 *
 * A reset runs first so the assertions about "exactly the seed graph" hold on a
 * stack that has already served contributions, whether from a previous run of
 * this suite or from a rehearsal.
 */
test.describe('public graph snapshot', () => {
  // A dedicated context: test-scoped fixtures are not available in `beforeAll`.
  test.beforeAll(async () => {
    const context = await newAnonymousContext();
    try {
      await resetRound(context);
    } finally {
      await context.dispose();
    }
  });

  test('GET /api/health returns 200', async ({ request }) => {
    const response = await request.get(paths.health);
    expect(response.status()).toBe(200);

    const body = await response.json();
    expect(body.status).toBe('ok');
    expect(typeof body.version).toBe('string');
    expect(Number.isNaN(Date.parse(body.time))).toBe(false);
  });

  test('returns contract version 1, the seeded root, three suppliers and three seed edges', async ({
    request,
  }) => {
    const response = await request.get(paths.graph());
    expect(response.status()).toBe(200);

    const snapshot = await response.json();
    expect(snapshot.contractVersion).toBe(CONTRACT_VERSION);

    // Session summary
    expect(snapshot.session.id).toBe(SEED.sessionId);
    expect(snapshot.session.slug).toBe(SESSION_SLUG);
    expect(snapshot.session.status).toBe('open');
    expect(snapshot.session.currentRound).toBeGreaterThanOrEqual(1);
    expect(snapshot.session.rootOrganizationId).toBe(SEED.root);
    expect(Number.isNaN(Date.parse(snapshot.serverTime))).toBe(false);

    // The four seeded organizations, and nothing else right after a reset.
    const nodeIds = snapshot.nodes.map((node: any) => node.id);
    expect(new Set(nodeIds)).toEqual(new Set(SEED_ORGANIZATION_IDS));
    expect(nodeIds).toHaveLength(SEED_ORGANIZATION_IDS.length);

    for (const node of snapshot.nodes) {
      expect(node.name).toBe(SEED_ORGANIZATION_NAMES[node.id]);
      expect(node.isSeed).toBe(true);
      // The reveal must be earned by the audience: no seeded external exposure.
      expect(node.jurisdiction).toBe('europe');
    }

    const root = snapshot.nodes.find((node: any) => node.id === SEED.root);
    expect(root.organizationType).toBe('government');

    // The three seed edges: root -> Alpine, root -> Rhine, Alpine -> Baltic.
    const edgeIds = snapshot.edges.map((edge: any) => edge.id);
    expect(new Set(edgeIds)).toEqual(new Set(SEED_EDGE_IDS));
    expect(edgeIds).toHaveLength(SEED_EDGE_IDS.length);

    for (const edge of snapshot.edges) {
      expect(edge.isSeed).toBe(true);
      expect(edge.status).toBe('active');
      expect(Number.isNaN(Date.parse(edge.createdAt))).toBe(false);
    }

    const pairs = snapshot.edges.map(
      (edge: any) => `${edge.sourceOrganizationId}->${edge.targetOrganizationId}`,
    );
    expect(new Set(pairs)).toEqual(
      new Set([
        `${SEED.root}->${SEED.alpineCivicSystems}`,
        `${SEED.root}->${SEED.rhinePublicNetworks}`,
        `${SEED.alpineCivicSystems}->${SEED.balticDataWorks}`,
      ]),
    );
  });

  test('invariant: every edge endpoint is present in nodes', async ({ request }) => {
    const snapshot = await getSnapshot(request);
    const nodeIds = new Set(snapshot.nodes.map((node) => node.id));

    for (const edge of snapshot.edges) {
      expect(
        nodeIds.has(edge.sourceOrganizationId),
        `edge ${edge.id} references missing source node ${edge.sourceOrganizationId}`,
      ).toBe(true);
      expect(
        nodeIds.has(edge.targetOrganizationId),
        `edge ${edge.id} references missing target node ${edge.targetOrganizationId}`,
      ).toBe(true);
    }

    // The root is always part of the snapshot, even with no edges at all.
    expect(nodeIds.has(snapshot.session.rootOrganizationId)).toBe(true);
  });

  test('ordering is deterministic across two consecutive calls', async ({ request }) => {
    const first = await getSnapshot(request);
    const second = await getSnapshot(request);

    expect(second.nodes.map((node) => node.id)).toEqual(first.nodes.map((node) => node.id));
    expect(second.edges.map((edge) => edge.id)).toEqual(first.edges.map((edge) => edge.id));
  });

  test('unknown slug returns 404 SESSION_NOT_FOUND', async ({ request }) => {
    const response = await request.get(paths.graph('no-such-session'));
    await expectApiError(response, 404, 'SESSION_NOT_FOUND');
  });
});
