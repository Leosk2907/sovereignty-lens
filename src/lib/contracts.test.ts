import { describe, expect, it } from "vitest";
import {
  CONTRACT_VERSION,
  adminSessionResultSchema,
  contributionRequestSchema,
  graphEventSchema,
  graphSnapshotSchema,
} from "@/lib/contracts";
import { demoGraphFixture } from "@/lib/fixtures";

describe("data contract", () => {
  it("parses the canonical graph fixture", () => {
    expect(graphSnapshotSchema.parse(demoGraphFixture)).toEqual(demoGraphFixture);
  });

  it("rejects unknown request fields", () => {
    expect(() =>
      contributionRequestSchema.parse({
        contractVersion: CONTRACT_VERSION,
        anonymousClientId: "00000000-0000-4000-8000-000000000401",
        sourceOrganizationId: demoGraphFixture.session.rootOrganizationId,
        target: {
          name: "Example Supplier",
          organizationType: "software",
          jurisdiction: "europe",
        },
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
