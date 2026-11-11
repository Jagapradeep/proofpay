// Browser-side API client. Calls go to our own Next.js /api proxy, which adds the API key.
import type { AuditEvent, Job, LedgerRow, Milestone, OrderInfo, VerificationReport } from "./types";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    const m = data && typeof data === "object" && "message" in data ? (data as { message: unknown }).message : null;
    const msg = m ? String(m) : `HTTP ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export interface NewJob {
  title: string;
  description: string;
  clientEmail: string;
  freelancerEmail: string;
  currency: string;
  milestones: { title: string; amount: number }[];
}

export const api = {
  jobs: () => call<Job[]>("GET", "/jobs"),
  job: (id: string) => call<Job>("GET", `/jobs/${id}`),
  createJob: (j: NewJob) => call<Job>("POST", "/jobs", j),
  compileCriteria: (id: string, fresh = false) => call<Job>("POST", `/jobs/${id}/criteria${fresh ? "?fresh=true" : ""}`),
  acceptCriteria: (id: string, party: "CLIENT" | "FREELANCER") =>
    call<Job>("POST", `/jobs/${id}/criteria/accept`, { party }),
  createOrder: (id: string) => call<OrderInfo>("POST", `/jobs/${id}/order`),
  authorize: (id: string) => call<Job>("POST", `/jobs/${id}/authorize`),
  voidFunding: (id: string) => call<Job>("POST", `/jobs/${id}/void`),
  submit: (milestoneId: string, deliverableUrl: string) =>
    call<Milestone>("POST", `/milestones/${milestoneId}/submit`, { deliverableUrl }),
  verify: (milestoneId: string) => call<VerificationReport>("POST", `/milestones/${milestoneId}/verify`),
  refreshRelease: (releaseId: string) => call<void>("POST", `/releases/${releaseId}/refresh`),
  retryPayout: (releaseId: string) => call<void>("POST", `/releases/${releaseId}/retry-payout`),
  ledger: () => call<LedgerRow[]>("GET", "/ledger"),
  audit: (jobId: string) => call<AuditEvent[]>("GET", `/jobs/${jobId}/audit`),
};

export function parseJson<T>(s: string | null | undefined): T | null {
  if (!s) return null;
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
}
