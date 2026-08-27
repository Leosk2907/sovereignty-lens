import { z } from "zod";

export const CONTRACT_VERSION = 1 as const;

export const jurisdictionSchema = z.enum([
  "europe",
  "united_states",
  "china",
  "other_external",
  "unknown",
]);

export const organizationTypeSchema = z.enum([
  "government",
  "cloud",
  "software",
  "hardware",
  "telecom",
  "consulting",
  "logistics",
  "finance",
  "other",
]);

export const sessionStatusSchema = z.enum(["open", "paused"]);
export const dependencyStatusSchema = z.enum(["active", "hidden"]);
export const adminInvalidationReasonSchema = z.enum([
  "pause",
  "resume",
  "hide",
  "restore",
  "undo",
  "reset",
]);

const uuidSchema = z.uuid();
const timestampSchema = z.iso.datetime({ offset: true });
const versionSchema = z.literal(CONTRACT_VERSION);

export const sessionSummarySchema = z.strictObject({
  id: uuidSchema,
  slug: z.string().min(1),
  title: z.string().min(1),
  status: sessionStatusSchema,
  currentRound: z.number().int().positive(),
  rootOrganizationId: uuidSchema,
});

export const graphNodeSchema = z.strictObject({
  id: uuidSchema,
  name: z.string().trim().min(2).max(60),
  organizationType: organizationTypeSchema,
  jurisdiction: jurisdictionSchema,
  isSeed: z.boolean(),
});

export const graphEdgeSchema = z.strictObject({
  id: uuidSchema,
  sourceOrganizationId: uuidSchema,
  targetOrganizationId: uuidSchema,
  isSeed: z.boolean(),
  status: dependencyStatusSchema,
  createdAt: timestampSchema,
});

export const graphSnapshotSchema = z.strictObject({
  contractVersion: versionSchema,
  session: sessionSummarySchema,
  nodes: z.array(graphNodeSchema),
  edges: z.array(graphEdgeSchema),
  serverTime: timestampSchema,
});

const audienceOrganizationTypeSchema = organizationTypeSchema.exclude(["government"]);

export const contributionRequestSchema = z.strictObject({
  contractVersion: versionSchema,
  anonymousClientId: uuidSchema,
  sourceOrganizationId: uuidSchema,
  target: z.strictObject({
    name: z.string().trim().min(2).max(60),
    organizationType: audienceOrganizationTypeSchema,
    jurisdiction: jurisdictionSchema,
  }),
});

export const contributionResultSchema = z.strictObject({
  contractVersion: versionSchema,
  eventId: uuidSchema,
  round: z.number().int().positive(),
  node: graphNodeSchema,
  edge: graphEdgeSchema,
});

export const apiErrorCodeSchema = z.enum([
  "VALIDATION_ERROR",
  "SESSION_NOT_FOUND",
  "SOURCE_NOT_FOUND",
  "DUPLICATE_DEPENDENCY",
  "ALREADY_CONTRIBUTED",
  "SESSION_PAUSED",
  "ROUND_CAPACITY_REACHED",
  "UNAUTHORIZED",
  "FORBIDDEN",
  "NOT_FOUND",
  "INTERNAL_ERROR",
]);

export const apiErrorResponseSchema = z.strictObject({
  contractVersion: versionSchema,
  error: z.strictObject({
    code: apiErrorCodeSchema,
    message: z.string(),
    retryable: z.boolean(),
    field: z.string().optional(),
  }),
});

export const dependencyCreatedEventSchema = z.strictObject({
  contractVersion: versionSchema,
  event: z.literal("dependency.created"),
  eventId: uuidSchema,
  sessionSlug: z.string().min(1),
  round: z.number().int().positive(),
  node: graphNodeSchema,
  edge: graphEdgeSchema,
  occurredAt: timestampSchema,
});

export const graphInvalidatedEventSchema = z.strictObject({
  contractVersion: versionSchema,
  event: z.literal("graph.invalidated"),
  eventId: uuidSchema,
  sessionSlug: z.string().min(1),
  round: z.number().int().positive(),
  reason: adminInvalidationReasonSchema,
  occurredAt: timestampSchema,
});

export const graphEventSchema = z.discriminatedUnion("event", [
  dependencyCreatedEventSchema,
  graphInvalidatedEventSchema,
]);

export const adminLoginRequestSchema = z.strictObject({
  contractVersion: versionSchema,
  password: z.string().min(1),
});

export const adminLoginResultSchema = z.strictObject({
  contractVersion: versionSchema,
  authenticated: z.literal(true),
});

export const adminSessionResultSchema = z.strictObject({
  contractVersion: versionSchema,
  authenticated: z.literal(true),
  session: sessionSummarySchema,
});

export const adminLogoutResultSchema = z.strictObject({
  contractVersion: versionSchema,
  authenticated: z.literal(false),
});

export const adminActionSchema = z.discriminatedUnion("type", [
  z.strictObject({ type: z.literal("pause") }),
  z.strictObject({ type: z.literal("resume") }),
  z.strictObject({ type: z.literal("reset") }),
  z.strictObject({ type: z.literal("undo") }),
]);

export const adminActionRequestSchema = z.strictObject({
  contractVersion: versionSchema,
  action: adminActionSchema,
});

export const adminActionResultSchema = z.strictObject({
  contractVersion: versionSchema,
  eventId: uuidSchema,
  session: sessionSummarySchema,
});

export const dependencyStatusRequestSchema = z.strictObject({
  contractVersion: versionSchema,
  status: dependencyStatusSchema,
});

export const dependencyStatusResultSchema = z.strictObject({
  contractVersion: versionSchema,
  eventId: uuidSchema,
  edge: graphEdgeSchema,
});

export const adminDependencySchema = z.strictObject({
  edge: graphEdgeSchema,
  source: graphNodeSchema,
  target: graphNodeSchema,
});

export const adminDependencyListSchema = z.strictObject({
  contractVersion: versionSchema,
  session: sessionSummarySchema,
  dependencies: z.array(adminDependencySchema),
});

export type Jurisdiction = z.infer<typeof jurisdictionSchema>;
export type OrganizationType = z.infer<typeof organizationTypeSchema>;
export type SessionStatus = z.infer<typeof sessionStatusSchema>;
export type DependencyStatus = z.infer<typeof dependencyStatusSchema>;
export type AdminInvalidationReason = z.infer<typeof adminInvalidationReasonSchema>;
export type SessionSummary = z.infer<typeof sessionSummarySchema>;
export type GraphNode = z.infer<typeof graphNodeSchema>;
export type GraphEdge = z.infer<typeof graphEdgeSchema>;
export type GraphSnapshot = z.infer<typeof graphSnapshotSchema>;
export type ContributionRequest = z.infer<typeof contributionRequestSchema>;
export type ContributionResult = z.infer<typeof contributionResultSchema>;
export type ApiErrorCode = z.infer<typeof apiErrorCodeSchema>;
export type ApiErrorResponse = z.infer<typeof apiErrorResponseSchema>;
export type DependencyCreatedEvent = z.infer<typeof dependencyCreatedEventSchema>;
export type GraphInvalidatedEvent = z.infer<typeof graphInvalidatedEventSchema>;
export type GraphEvent = z.infer<typeof graphEventSchema>;
export type AdminLoginRequest = z.infer<typeof adminLoginRequestSchema>;
export type AdminLoginResult = z.infer<typeof adminLoginResultSchema>;
export type AdminSessionResult = z.infer<typeof adminSessionResultSchema>;
export type AdminLogoutResult = z.infer<typeof adminLogoutResultSchema>;
export type AdminAction = z.infer<typeof adminActionSchema>;
export type AdminActionRequest = z.infer<typeof adminActionRequestSchema>;
export type AdminActionResult = z.infer<typeof adminActionResultSchema>;
export type DependencyStatusRequest = z.infer<typeof dependencyStatusRequestSchema>;
export type DependencyStatusResult = z.infer<typeof dependencyStatusResultSchema>;
export type AdminDependency = z.infer<typeof adminDependencySchema>;
export type AdminDependencyList = z.infer<typeof adminDependencyListSchema>;
