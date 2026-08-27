import { z } from "zod";
import {
  CONTRACT_VERSION,
  graphEdgeSchema,
  graphNodeSchema,
  jurisdictionSchema,
  organizationTypeSchema,
} from "@/lib/contracts";

/**
 * Prototype-only extension of the shared contract: one contribution
 * introduces the audience member's own company as a new node, with a set of
 * existing EU customers (edges: customer -> company) and a set of
 * dependencies (edges: company -> dependency, EU or external).
 *
 * Not part of contracts/data-contract.md yet. Kept in its own module so the
 * canonical contract and the real mock-store/api-client stay untouched while
 * this idea is being evaluated.
 */

const versionSchema = z.literal(CONTRACT_VERSION);
const audienceOrganizationTypeSchema = organizationTypeSchema.exclude(["government"]);
const companyNameSchema = z.string().trim().min(2).max(60);

export const MAX_CUSTOMERS = 3;
export const MAX_DEPENDENCIES = 3;

export const newDependencySchema = z.strictObject({
  name: companyNameSchema,
  organizationType: audienceOrganizationTypeSchema,
  jurisdiction: jurisdictionSchema,
});

export const companyContributionRequestSchema = z.strictObject({
  contractVersion: versionSchema,
  anonymousClientId: z.uuid(),
  company: z.strictObject({
    name: companyNameSchema,
    organizationType: audienceOrganizationTypeSchema,
    jurisdiction: jurisdictionSchema,
  }),
  customerOrganizationIds: z.array(z.uuid()).min(1).max(MAX_CUSTOMERS),
  dependencies: z.array(newDependencySchema).min(1).max(MAX_DEPENDENCIES),
});
export type CompanyContributionRequest = z.infer<typeof companyContributionRequestSchema>;

export const companyContributionResultSchema = z.strictObject({
  contractVersion: versionSchema,
  round: z.number().int().positive(),
  company: graphNodeSchema,
  customerEdges: z.array(graphEdgeSchema),
  dependencyEdges: z.array(graphEdgeSchema),
});
export type CompanyContributionResult = z.infer<typeof companyContributionResultSchema>;
