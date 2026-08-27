import { test, expect } from '@playwright/test';
import {
  CONTRACT_VERSION,
  SEED,
  getSnapshot,
  newClientId,
  paths,
  resetRound,
  uniqueCompanyName,
} from './helpers';

test.describe('company profile contribution', () => {
  test.beforeEach(async ({ request }) => {
    await resetRound(request);
  });

  test('commits the company and every customer/provider connection atomically', async ({
    request,
  }) => {
    const companyName = uniqueCompanyName('Danube Systems');
    const firstProvider = uniqueCompanyName('Atlantic Cloud');
    const secondProvider = uniqueCompanyName('Dragon Hardware');

    const response = await request.post(paths.companyContributions(), {
      data: {
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: newClientId(),
        company: {
          name: companyName,
          organizationType: 'software',
          jurisdiction: 'europe',
        },
        customerOrganizationIds: [SEED.alpineCivicSystems, SEED.balticDataWorks],
        dependencies: [
          {
            name: firstProvider,
            organizationType: 'cloud',
            jurisdiction: 'united_states',
          },
          {
            name: secondProvider,
            organizationType: 'hardware',
            jurisdiction: 'china',
          },
        ],
      },
    });

    expect(response.status()).toBe(201);
    const result = await response.json();
    expect(result.contractVersion).toBe(CONTRACT_VERSION);
    expect(result.company).toMatchObject({
      name: companyName,
      organizationType: 'software',
      jurisdiction: 'europe',
      isSeed: false,
    });
    expect(result.customerConnections).toHaveLength(2);
    expect(result.dependencyConnections).toHaveLength(2);

    expect(
      new Set(
        result.customerConnections.map(
          (connection: { edge: { sourceOrganizationId: string } }) =>
            connection.edge.sourceOrganizationId,
        ),
      ),
    ).toEqual(new Set([SEED.alpineCivicSystems, SEED.balticDataWorks]));
    for (const connection of result.customerConnections) {
      expect(connection.eventId).toMatch(/^[0-9a-f-]{36}$/);
      expect(connection.node).toEqual(result.company);
      expect(connection.edge.targetOrganizationId).toBe(result.company.id);
    }

    expect(
      result.dependencyConnections.map(
        (connection: { node: { name: string } }) => connection.node.name,
      ),
    ).toEqual([firstProvider, secondProvider]);
    for (const connection of result.dependencyConnections) {
      expect(connection.eventId).toMatch(/^[0-9a-f-]{36}$/);
      expect(connection.edge.sourceOrganizationId).toBe(result.company.id);
      expect(connection.edge.targetOrganizationId).toBe(connection.node.id);
    }

    const snapshot = await getSnapshot(request);
    const committedEdgeIds = [
      ...result.customerConnections,
      ...result.dependencyConnections,
    ].map((connection) => connection.edge.id);
    expect(snapshot.nodes.some((node) => node.id === result.company.id)).toBe(true);
    expect(
      committedEdgeIds.every((id) => snapshot.edges.some((edge) => edge.id === id)),
    ).toBe(true);
  });
});
