// Shapes returned by the Spring Boot API (see backend ApiDtos and VerificationService.Report).

export type JobStatus = "DRAFT" | "AWAITING_FUNDING" | "FUNDED" | "COMPLETED" | "CANCELLED";
export type MilestoneStatus = "PENDING" | "SUBMITTED" | "VERIFIED" | "NEEDS_REVIEW" | "RELEASING" | "PAID";
export type ReleaseStatus =
  | "VERIFIED" | "CAPTURE_PENDING" | "CAPTURED" | "CAPTURE_FAILED"
  | "PAYOUT_PENDING" | "PAID" | "PAYOUT_FAILED";

export interface Criterion {
  id: string;
  description: string;
  howToCheck: string;
}

export interface Milestone {
  id: string;
  seq: number;
  title: string;
  amount: number;
  status: MilestoneStatus;
  deliverableUrl: string | null;
  criteriaJson: string | null;
  verificationJson: string | null;
}

export interface Funding {
  paypalOrderId: string | null;
  paypalAuthorizationId: string | null;
  amount: number;
  capturedAmount: number;
  remaining: number;
  status: string;
  expiresAt: string | null;
}

export interface Job {
  id: string;
  title: string;
  description: string;
  clientEmail: string;
  freelancerEmail: string;
  currency: string;
  totalAmount: number;
  status: JobStatus;
  createdAt: string;
  clientAcceptedCriteriaAt: string | null;
  freelancerAcceptedCriteriaAt: string | null;
  milestones: Milestone[];
  funding: Funding | null;
}

export interface CheckedEvidence {
  path: string;
  quote: string;
  verified: boolean;
}

export interface CheckedCriterion {
  criterionId: string;
  passed: boolean;
  score: number;
  note: string | null;
  evidence: CheckedEvidence[];
  proven: boolean;
}

export interface Finding {
  path: string;
  line: number;
  rule: string;
  snippet: string;
}

export interface VerificationReport {
  milestoneId: string;
  outcome: "RELEASED" | "BLOCKED" | "NO_VERDICT";
  repoUrl: string;
  commitSha: string;
  filesFetched: number;
  filesRead: string[];
  criteria: CheckedCriterion[];
  scannerFindings: Finding[];
  agentInjectionSuspected: boolean;
  injectionFlagged: boolean;
  proposedAmount: number | null;
  agentConfidence: number | null;
  effectiveConfidence: number | null;
  adjustments: string[];
  gateApproved: boolean | null;
  gateReasons: string[];
  releaseId: string | null;
  agentSummary: string | null;
  agentReply: string | null;
  model: string;
  durationMs: number;
  verifiedAt: string;
}

export interface LedgerRow {
  releaseId: string;
  jobId: string;
  jobTitle: string;
  milestoneSeq: number;
  milestoneTitle: string;
  amount: number;
  currency: string;
  payeeEmail: string;
  status: ReleaseStatus;
  finalCapture: boolean;
  paypalAuthorizationId: string | null;
  paypalCaptureId: string | null;
  paypalPayoutBatchId: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEvent {
  id: number;
  jobId: string | null;
  milestoneId: string | null;
  releaseId: string | null;
  type: string;
  actor: string;
  detail: string | null;
  createdAt: string;
}

export interface OrderInfo {
  orderId: string;
  approveUrl: string;
  status: string;
}
