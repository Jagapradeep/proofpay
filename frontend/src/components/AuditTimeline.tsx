import { label, when } from "@/lib/format";
import type { AuditEvent } from "@/lib/types";

const ACTOR: Record<string, string> = {
  AGENT: "bg-violet-100 text-violet-700",
  POLICY_GATE: "bg-ink text-white",
  PAYPAL: "bg-brand-soft text-brand",
  CLIENT: "bg-amber-100 text-amber-800",
  FREELANCER: "bg-teal-100 text-teal-800",
  SYSTEM: "bg-slate-100 text-slate-600",
};

const IMPORTANT = new Set(["GATE_BLOCKED", "GATE_APPROVED", "PAYOUT_SUCCEEDED", "JOB_COMPLETED", "FUNDS_AUTHORIZED"]);

/** Every step, who did it, and why. The gate and PayPal rows show that the LLM never moves money itself. */
export function AuditTimeline({ events }: { events: AuditEvent[] }) {
  return (
    <section className="card p-5">
      <h2 className="section-title">Audit trail</h2>
      <p className="mt-1 text-sm text-muted">Append-only. Each row says who acted: the AI agent, the policy gate (code), PayPal, or a person.</p>
      <ol className="mt-4 space-y-2">
        {events.map((e) => (
          <li key={e.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 text-sm">
            <span className="w-36 shrink-0 font-mono text-xs text-muted">{when(e.createdAt)}</span>
            <span className={`rounded px-2 py-0.5 text-xs font-semibold ${ACTOR[e.actor] ?? ACTOR.SYSTEM}`}>{label(e.actor)}</span>
            <div className="min-w-0 flex-1">
              <span className={IMPORTANT.has(e.type) ? "font-semibold" : ""}>{label(e.type)}</span>
              {e.detail && <Detail json={e.detail} />}
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function Detail({ json }: { json: string }) {
  let pretty = json;
  let reasons: string[] | null = null;
  try {
    const obj = JSON.parse(json);
    pretty = JSON.stringify(obj, null, 2);
    if (Array.isArray(obj?.reasons) && obj.reasons.length) reasons = obj.reasons;
  } catch {
    // show raw
  }
  return (
    <>
      {reasons && <span className="ml-2 text-xs text-bad">{reasons.join(" · ")}</span>}
      <details className="mt-0.5">
        <summary className="cursor-pointer text-xs text-muted">details</summary>
        <pre className="mt-1 max-h-64 overflow-auto rounded bg-slate-50 p-2 text-xs">{pretty}</pre>
      </details>
    </>
  );
}
