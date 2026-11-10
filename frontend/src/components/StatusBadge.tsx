import { label } from "@/lib/format";

const TONES: Record<string, string> = {
  // good
  PAID: "ok", COMPLETED: "ok", FUNDED: "ok", RELEASED: "ok", CAPTURED: "ok", AUTHORIZED: "ok",
  // in progress
  AWAITING_FUNDING: "info", SUBMITTED: "info", RELEASING: "info", VERIFIED: "info", CAPTURE_PENDING: "info",
  PAYOUT_PENDING: "info", PARTIALLY_CAPTURED: "info", CREATED: "info",
  // needs attention
  NEEDS_REVIEW: "warn", BLOCKED: "bad", NO_VERDICT: "warn",
  // failed / ended
  CAPTURE_FAILED: "bad", PAYOUT_FAILED: "bad", CANCELLED: "neutral", VOIDED: "neutral", EXPIRED: "bad",
  DRAFT: "neutral", PENDING: "neutral",
};

const CLASSES: Record<string, string> = {
  ok: "bg-ok-soft text-ok",
  info: "bg-brand-soft text-brand",
  warn: "bg-warn-soft text-warn",
  bad: "bg-bad-soft text-bad",
  neutral: "bg-slate-100 text-slate-600",
};

export function StatusBadge({ status, className = "" }: { status: string; className?: string }) {
  const tone = TONES[status] ?? "neutral";
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${CLASSES[tone]} ${className}`}>
      {label(status)}
    </span>
  );
}
