"use client";

import type { ZodType } from "zod";
import {
  CONTRACT_VERSION,
  adminActionResultSchema,
  adminDependencyListSchema,
  adminLoginResultSchema,
  adminLogoutResultSchema,
  adminSessionResultSchema,
  apiErrorResponseSchema,
  companyContributionRequestSchema,
  companyContributionResultSchema,
  dependencyStatusResultSchema,
  graphSnapshotSchema,
  type AdminAction,
  type AdminActionResult,
  type AdminDependencyList,
  type AdminLoginResult,
  type AdminLogoutResult,
  type AdminSessionResult,
  type ApiErrorCode,
  type CompanyContributionRequest,
  type CompanyContributionResult,
  type DependencyStatus,
  type DependencyStatusResult,
  type GraphSnapshot,
} from "@/lib/contracts";
import {
  mockAdminAction,
  mockGetAdminDependencies,
  mockGetAdminSession,
  mockGetGraph,
  mockLogin,
  mockLogout,
  mockSetDependencyStatus,
  mockSubmitCompanyContribution,
} from "@/lib/mock-store";

export const isMockMode =
  process.env.NEXT_PUBLIC_USE_MOCK_API === "true" ||
  !process.env.NEXT_PUBLIC_SUPABASE_URL;

export class ApiClientError extends Error {
  constructor(
    public readonly code: ApiErrorCode,
    message: string,
    public readonly retryable = false,
  ) {
    super(message);
    this.name = "ApiClientError";
  }
}

function mockError(error: unknown): never {
  const code = error instanceof Error ? error.message : "INTERNAL_ERROR";
  const knownCode = [
    "VALIDATION_ERROR", "SESSION_NOT_FOUND", "SOURCE_NOT_FOUND", "DUPLICATE_DEPENDENCY",
    "ALREADY_CONTRIBUTED", "SESSION_PAUSED", "ROUND_CAPACITY_REACHED", "UNAUTHORIZED",
    "FORBIDDEN", "NOT_FOUND", "INTERNAL_ERROR",
  ].includes(code) ? (code as ApiErrorCode) : "INTERNAL_ERROR";
  throw new ApiClientError(knownCode, knownCode.replaceAll("_", " ").toLowerCase(), knownCode === "INTERNAL_ERROR");
}

async function request<T>(path: string, schema: ZodType<T>, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { "Content-Type": "application/json", ...init?.headers },
    });
  } catch {
    throw new ApiClientError("INTERNAL_ERROR", "Could not reach the server.", true);
  }
  const json: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const parsed = apiErrorResponseSchema.safeParse(json);
    if (parsed.success) {
      throw new ApiClientError(parsed.data.error.code, parsed.data.error.message, parsed.data.error.retryable);
    }
    throw new ApiClientError("INTERNAL_ERROR", "The server returned an unexpected response.", true);
  }
  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    throw new ApiClientError("INTERNAL_ERROR", "The server response did not match contract version 1.", true);
  }
  return parsed.data;
}

export async function getGraphSnapshot(): Promise<GraphSnapshot> {
  if (isMockMode) return mockGetGraph();
  return request("/api/sessions/demo/graph", graphSnapshotSchema);
}

export async function submitCompanyContribution(
  input: CompanyContributionRequest,
): Promise<CompanyContributionResult> {
  const body = companyContributionRequestSchema.parse(input);
  if (isMockMode) return mockSubmitCompanyContribution(body).catch(mockError);
  return request("/api/sessions/demo/company-contributions", companyContributionResultSchema, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function loginAdmin(password: string): Promise<AdminLoginResult> {
  if (isMockMode) return mockLogin(password).catch(mockError);
  return request("/api/admin/login", adminLoginResultSchema, {
    method: "POST",
    body: JSON.stringify({ contractVersion: CONTRACT_VERSION, password }),
  });
}

export async function getAdminSession(): Promise<AdminSessionResult> {
  if (isMockMode) return mockGetAdminSession().catch(mockError);
  return request("/api/admin/session", adminSessionResultSchema);
}

export async function logoutAdmin(): Promise<AdminLogoutResult> {
  if (isMockMode) return mockLogout();
  return request("/api/admin/logout", adminLogoutResultSchema, { method: "POST", body: "{}" });
}

export async function getAdminDependencies(): Promise<AdminDependencyList> {
  if (isMockMode) return mockGetAdminDependencies().catch(mockError);
  return request("/api/admin/sessions/demo/dependencies", adminDependencyListSchema);
}

export async function runAdminAction(action: AdminAction): Promise<AdminActionResult> {
  if (isMockMode) return mockAdminAction(action).catch(mockError);
  return request("/api/admin/sessions/demo/actions", adminActionResultSchema, {
    method: "POST",
    body: JSON.stringify({ contractVersion: CONTRACT_VERSION, action }),
  });
}

export async function setDependencyStatus(id: string, status: DependencyStatus): Promise<DependencyStatusResult> {
  if (isMockMode) return mockSetDependencyStatus(id, status).catch(mockError);
  return request(`/api/admin/dependencies/${id}`, dependencyStatusResultSchema, {
    method: "PATCH",
    body: JSON.stringify({ contractVersion: CONTRACT_VERSION, status }),
  });
}
