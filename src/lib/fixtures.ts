import type { GraphSnapshot } from "@/lib/contracts";
import { CONTRACT_VERSION } from "@/lib/contracts";

const createdAt = "2026-08-27T08:00:00.000Z";

export const demoGraphFixture = {
  contractVersion: CONTRACT_VERSION,
  session: {
    id: "00000000-0000-4000-8000-000000000001",
    slug: "demo",
    title: "European Digital Services Agency",
    status: "open",
    currentRound: 1,
    rootOrganizationId: "00000000-0000-4000-8000-000000000101",
  },
  nodes: [
    {
      id: "00000000-0000-4000-8000-000000000101",
      name: "European Digital Services Agency",
      organizationType: "government",
      jurisdiction: "europe",
      isSeed: true,
    },
    {
      id: "00000000-0000-4000-8000-000000000102",
      name: "Alpine Civic Systems",
      organizationType: "software",
      jurisdiction: "europe",
      isSeed: true,
    },
    {
      id: "00000000-0000-4000-8000-000000000103",
      name: "Baltic Data Works",
      organizationType: "cloud",
      jurisdiction: "europe",
      isSeed: true,
    },
    {
      id: "00000000-0000-4000-8000-000000000104",
      name: "Rhine Public Networks",
      organizationType: "telecom",
      jurisdiction: "europe",
      isSeed: true,
    },
  ],
  edges: [
    {
      id: "00000000-0000-4000-8000-000000000201",
      sourceOrganizationId: "00000000-0000-4000-8000-000000000101",
      targetOrganizationId: "00000000-0000-4000-8000-000000000102",
      isSeed: true,
      status: "active",
      createdAt,
    },
    {
      id: "00000000-0000-4000-8000-000000000202",
      sourceOrganizationId: "00000000-0000-4000-8000-000000000101",
      targetOrganizationId: "00000000-0000-4000-8000-000000000104",
      isSeed: true,
      status: "active",
      createdAt,
    },
    {
      id: "00000000-0000-4000-8000-000000000203",
      sourceOrganizationId: "00000000-0000-4000-8000-000000000102",
      targetOrganizationId: "00000000-0000-4000-8000-000000000103",
      isSeed: true,
      status: "active",
      createdAt,
    },
  ],
  serverTime: createdAt,
} satisfies GraphSnapshot;
