import { describe, expect, it } from "vitest";
import {
  CONTRACT_VERSION,
  adminSessionResultSchema,
  companyContributionRequestSchema,
  graphEventSchema,
  graphSnapshotSchema,
} from "@/lib/contracts";
import { demoGraphFixture, populatedDemoGraphFixture } from "@/lib/fixtures";

describe("data contract", () => {
  it("parses the canonical graph fixture", () => {
    expect(graphSnapshotSchema.parse(demoGraphFixture)).toEqual(demoGraphFixture);
  });

  it("parses the populated 28-organization demo network", () => {
    const parsed = graphSnapshotSchema.parse(populatedDemoGraphFixture);
    expect(parsed.nodes).toHaveLength(28);
    expect(parsed.edges).toHaveLength(37);
  });

  it("rejects unknown request fields", () => {
    expect(() =>
      companyContributionRequestSchema.parse({
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: "00000000-0000-4000-8000-000000000401",
        company: {
          name: "Example Company",
          organizationType: "software",
          jurisdiction: "europe",
        },
        customerOrganizationIds: [demoGraphFixture.session.rootOrganizationId],
        dependencies: [{
          name: "Example Supplier",
          organizationType: "cloud",
          jurisdiction: "united_states",
        }],
        unexpected: true,
      }),
    ).toThrow();
  });

  it("uses the same canonical records for HTTP and Realtime", () => {
    const node = demoGraphFixture.nodes[1];
    const edge = demoGraphFixture.edges[0];
    const eventId = "00000000-0000-4000-8000-000000000501";
    expect(
      graphEventSchema.parse({
        contractVersion: CONTRACT_VERSION,
        event: "dependency.created",
        eventId,
        sessionSlug: "demo",
        round: 1,
        node,
        edge,
        occurredAt: edge.createdAt,
      }),
    ).toMatchObject({ eventId, node, edge });
  });

  it("parses one European company with one-to-three customers and dependencies", () => {
    expect(
      companyContributionRequestSchema.parse({
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: "00000000-0000-4000-8000-000000000401",
        company: {
          name: "Northstar Civic Systems",
          organizationType: "software",
          jurisdiction: "europe",
        },
        customerOrganizationIds: [demoGraphFixture.session.rootOrganizationId],
        dependencies: [
          {
            name: "Pacific Quantum Cloud",
            organizationType: "cloud",
            jurisdiction: "united_states",
          },
        ],
      }).company.jurisdiction,
    ).toBe("europe");
  });

  it("rejects a non-European contributed company and oversized batches", () => {
    const base = {
      contractVersion: CONTRACT_VERSION,
      anonymousClientId: "00000000-0000-4000-8000-000000000401",
      company: {
        name: "Northstar Civic Systems",
        organizationType: "software" as const,
        jurisdiction: "united_states",
      },
      customerOrganizationIds: [demoGraphFixture.session.rootOrganizationId],
      dependencies: [
        { name: "Provider One", organizationType: "cloud" as const, jurisdiction: "europe" as const },
      ],
    };
    expect(companyContributionRequestSchema.safeParse(base).success).toBe(false);
    expect(
      companyContributionRequestSchema.safeParse({
        ...base,
        company: { ...base.company, jurisdiction: "europe" },
        dependencies: [
          ...base.dependencies,
          { name: "Provider Two", organizationType: "cloud", jurisdiction: "europe" },
          { name: "Provider Three", organizationType: "cloud", jurisdiction: "europe" },
          { name: "Provider Four", organizationType: "cloud", jurisdiction: "europe" },
        ],
      }).success,
    ).toBe(false);
  });

  it("parses the presenter session contract", () => {
    expect(
      adminSessionResultSchema.parse({
        contractVersion: CONTRACT_VERSION,
        authenticated: true,
        session: demoGraphFixture.session,
      }).session.currentRound,
    ).toBe(1);
  });
});
