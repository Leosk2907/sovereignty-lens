import { test, expect } from '@playwright/test';
import {
  ABSENT_ORGANIZATION_ID,
  CONTRACT_VERSION,
  SEED,
  contribute,
  contributeOk,
  expectApiError,
  getSnapshot,
  newClientId,
  paths,
  resetRound,
  uniqueCompanyName,
} from './helpers';

/**
 * `POST /api/sessions/demo/dependencies` — the audience endpoint.
 *
 * Each test starts its own round, so contributor fingerprints and edges from
 * the previous test can never leak into this one. Reset is non-destructive: it
 * increments `currentRound` and keeps every seed record visible.
 */
test.describe('audience contribution', () => {
  test.beforeEach(async ({ request }) => {
    await resetRound(request);
  });

  test('happy path returns 201 and the contribution appears in the next snapshot', async ({
    request,
  }) => {
    const name = uniqueCompanyName('Meridian Civic Works');
    const before = await getSnapshot(request);

    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name,
      type: 'software',
      jurisdiction: 'europe',
    });
    expect(response.status()).toBe(201);

    const result = await response.json();
    expect(result.contractVersion).toBe(CONTRACT_VERSION);
    expect(result.round).toBe(before.session.currentRound);
    expect(result.eventId).toMatch(/^[0-9a-f-]{36}$/);

    // The canonical node.
    expect(result.node.name).toBe(name);
    expect(result.node.organizationType).toBe('software');
    expect(result.node.jurisdiction).toBe('europe');
    expect(result.node.isSeed).toBe(false);

    // The canonical edge: source depends on target.
    expect(result.edge.sourceOrganizationId).toBe(SEED.balticDataWorks);
    expect(result.edge.targetOrganizationId).toBe(result.node.id);
    expect(result.edge.isSeed).toBe(false);
    expect(result.edge.status).toBe('active');

    // The anonymous client id is never echoed back.
    expect(await response.text()).not.toContain('anonymousClientId');

    const after = await getSnapshot(request);
    const node = after.nodes.find((candidate) => candidate.id === result.node.id);
    const edge = after.edges.find((candidate) => candidate.id === result.edge.id);

    expect(node).toEqual(result.node);
    expect(edge).toEqual(result.edge);
  });

  test('the same company named from two sources is reused as one node with two edges', async ({
    request,
  }) => {
    const name = 'North Star Cloud';

    const first = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name,
      type: 'cloud',
      jurisdiction: 'united_states',
    });

    const second = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.rhinePublicNetworks,
      name,
      type: 'cloud',
      jurisdiction: 'united_states',
    });

    // One organization, two dependencies.
    expect(second.node.id).toBe(first.node.id);
    expect(second.edge.id).not.toBe(first.edge.id);
    expect(second.edge.sourceOrganizationId).toBe(SEED.rhinePublicNetworks);
    expect(second.edge.targetOrganizationId).toBe(first.node.id);

    // Case and surrounding whitespace collapse onto the same organization.
    const third = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.alpineCivicSystems,
      name: '  north star cloud ',
      type: 'cloud',
      jurisdiction: 'united_states',
    });
    expect(third.node.id).toBe(first.node.id);
    // Names are stored trimmed, and the reused record keeps its canonical name.
    expect(third.node.name.trim()).toBe(third.node.name);
    expect(third.node.name.toLowerCase()).toBe(name.toLowerCase());

    const snapshot = await getSnapshot(request);
    const matching = snapshot.nodes.filter(
      (node) => node.name.trim().toLowerCase() === name.toLowerCase(),
    );
    expect(matching).toHaveLength(1);

    const edgesToTarget = snapshot.edges.filter(
      (edge) => edge.targetOrganizationId === first.node.id,
    );
    expect(edgesToTarget).toHaveLength(3);
  });

  test('a second submission from the same browser in one round returns 409 ALREADY_CONTRIBUTED', async ({
    request,
  }) => {
    const clientId = newClientId();

    await contributeOk(request, {
      clientId,
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Firstsub'),
    });

    // Different source and different company: only the browser repeats.
    const response = await contribute(request, {
      clientId,
      sourceId: SEED.rhinePublicNetworks,
      name: uniqueCompanyName('Secondsub'),
      type: 'hardware',
      jurisdiction: 'china',
    });
    await expectApiError(response, 409, 'ALREADY_CONTRIBUTED');
  });

  test('the same edge from a different browser returns 409 DUPLICATE_DEPENDENCY', async ({
    request,
  }) => {
    const name = uniqueCompanyName('Dupeco');

    await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name,
    });

    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name,
    });
    await expectApiError(response, 409, 'DUPLICATE_DEPENDENCY');
  });

  test('a self-dependency returns 400 VALIDATION_ERROR', async ({ request }) => {
    // The target is named, so a self-dependency is expressed by naming the
    // source organization itself.
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: 'Baltic Data Works',
      type: 'cloud',
      jurisdiction: 'europe',
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('organizationType "government" returns 400 VALIDATION_ERROR', async ({ request }) => {
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Ministry Of Testing'),
      type: 'government',
      jurisdiction: 'europe',
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('an unknown sourceOrganizationId returns 404 SOURCE_NOT_FOUND', async ({ request }) => {
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: ABSENT_ORGANIZATION_ID,
      name: uniqueCompanyName('Orphanco'),
    });
    await expectApiError(response, 404, 'SOURCE_NOT_FOUND');
  });

  test('an unknown session slug returns 404 SESSION_NOT_FOUND', async ({ request }) => {
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Nowhereco'),
      slug: 'no-such-session',
    });
    await expectApiError(response, 404, 'SESSION_NOT_FOUND');
  });

  test('a 1-character name returns 400 VALIDATION_ERROR', async ({ request }) => {
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: 'A',
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('a 61-character name returns 400 VALIDATION_ERROR', async ({ request }) => {
    const tooLong = 'A'.repeat(61);
    expect(tooLong).toHaveLength(61);

    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: tooLong,
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('a 60-character name is accepted, proving the boundary is inclusive', async ({
    request,
  }) => {
    const atLimit = `Boundary ${'A'.repeat(51)}`;
    expect(atLimit).toHaveLength(60);

    const result = await contributeOk(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: atLimit,
    });
    expect(result.node.name).toBe(atLimit);
  });

  test('a wrong contractVersion returns 400 VALIDATION_ERROR', async ({ request }) => {
    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: uniqueCompanyName('Versionco'),
      contractVersion: 2,
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('an unknown extra JSON field returns 400 VALIDATION_ERROR', async ({ request }) => {
    // Request schemas are strict: unknown fields are rejected, never ignored.
    const response = await request.post(paths.dependencies(), {
      data: {
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: newClientId(),
        sourceOrganizationId: SEED.balticDataWorks,
        target: {
          name: uniqueCompanyName('Strictco'),
          organizationType: 'cloud',
          jurisdiction: 'europe',
        },
        somethingUnexpected: 'should be rejected',
      },
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('an unknown extra field inside target returns 400 VALIDATION_ERROR', async ({
    request,
  }) => {
    const response = await request.post(paths.dependencies(), {
      data: {
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: newClientId(),
        sourceOrganizationId: SEED.balticDataWorks,
        target: {
          name: uniqueCompanyName('Strictco'),
          organizationType: 'cloud',
          jurisdiction: 'europe',
          isSeed: true,
        },
      },
    });
    await expectApiError(response, 400, 'VALIDATION_ERROR');
  });

  test('HTML-shaped company text round-trips as inert plain text', async ({ request }) => {
    // Not an injection attempt: the contract says names are always rendered as
    // text, so the API must store and return the exact bytes without escaping
    // them into HTML entities. Any &lt; here would mean the backend is doing
    // presentation-layer escaping in the data layer.
    const hostile = '<img src=x onerror=alert(1)>';
    expect(hostile.length).toBeGreaterThanOrEqual(2);
    expect(hostile.length).toBeLessThanOrEqual(60);

    const response = await contribute(request, {
      clientId: newClientId(),
      sourceId: SEED.balticDataWorks,
      name: hostile,
      type: 'other',
      jurisdiction: 'unknown',
    });
    expect(response.status()).toBe(201);

    const raw = await response.text();
    const result = JSON.parse(raw);

    // Exact round-trip, character for character.
    expect(result.node.name).toBe(hostile);
    // No HTML entity mangling anywhere in the response body.
    expect(raw).toContain(hostile);
    expect(raw).not.toContain('&lt;');
    expect(raw).not.toContain('&gt;');
    expect(raw).not.toContain('&amp;');
    expect(raw).not.toContain('&#');
    expect(raw).not.toContain('\\u003c');

    // And again from the authoritative snapshot.
    const snapshot = await getSnapshot(request);
    const stored = snapshot.nodes.find((node) => node.id === result.node.id);
    expect(stored?.name).toBe(hostile);
  });
});
