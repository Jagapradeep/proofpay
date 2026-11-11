"use client";

import { useEffect, useState } from "react";
import { api, parseJson } from "@/lib/api";
import { money } from "@/lib/format";
import type { Job, LedgerRow, Milestone, VerificationReport } from "@/lib/types";
import { useApp } from "./Providers";
import { StatusBadge } from "./StatusBadge";
import { VerificationReportView } from "./VerificationReportView";

// Public demo deliverables (see README). Shown as one-click fills for judges.
const DEMO_REPOS = [
  { label: "good", url: "https://github.com/Jagapradeep/proofpay-demo-good" },
  { label: "partial", url: "https://github.com/Jagapradeep/proofpay-demo-partial" },
  { label: "injected", url: "https://github.com/Jagapradeep/proofpay-demo-injected" },
];

const PHASES = [
  { at: 0, text: "Fetching the repository at its latest commit…" },
  { at: 2, text: "Scanning every file for prompt injection (plain code)…" },
  { at: 4, text: "Verifier agent is reading files and checking each criterion…" },
  { at: 9, text: "Checking that every evidence quote really exists…" },
  { at: 11, text: "Policy gate is deciding; PayPal capture + payout if approved…" },
];

export function MilestoneCard({ job, milestone: m, release, onChange }: {
  job: Job; milestone: Milestone; release: LedgerRow | undefined; onChange: () => void;
}) {
  const { role } = useApp();
  const [url, setUrl] = useState(m.deliverableUrl ?? "");
  const [busy, setBusy] = useState<"submit" | "verify" | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const report = parseJson<VerificationReport>(m.verificationJson);
  const canSubmit = role === "FREELANCER" && job.status === "FUNDED" && (m.status === "PENDING" || m.status === "NEEDS_REVIEW");
  const canVerify = job.status === "FUNDED" && m.status === "SUBMITTED";

  useEffect(() => {
    if (busy !== "verify") return;
    setElapsed(0);
    const t = setInterval(() => setElapsed((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

  const run = async (kind: "submit" | "verify", fn: () => Promise<unknown>) => {
    setBusy(kind);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
      onChange();
    }
  };

  const phase = [...PHASES].reverse().find((p) => elapsed >= p.at)?.text;

  return (
    <article className="card p-5">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-7 w-7 place-items-center rounded-full bg-slate-100 text-sm font-bold">{m.seq}</span>
        <h3 className="font-semibold">{m.title}</h3>
        <StatusBadge status={m.status} />
        <span className="ml-auto font-semibold">{money(m.amount, job.currency)}</span>
      </div>

      {m.deliverableUrl && (
        <p className="mt-2 text-sm text-muted">
          Deliverable: <a className="font-medium text-brand hover:underline" href={m.deliverableUrl} target="_blank" rel="noreferrer">{m.deliverableUrl.replace("https://github.com/", "")}</a>
        </p>
      )}

      {canSubmit && (
        <div className="mt-4 rounded-lg bg-slate-50 p-4">
          <label className="mb-1 block text-sm font-medium">
            {m.status === "NEEDS_REVIEW" ? "Fix the work and resubmit a GitHub repo" : "Submit your work (public GitHub repo)"}
          </label>
          <div className="flex flex-wrap gap-2">
            <input className="input min-w-64 flex-1" placeholder="https://github.com/you/repo" value={url}
              onChange={(e) => setUrl(e.target.value)} />
            <button className="btn-primary" disabled={busy !== null || !url}
              onClick={() => run("submit", () => api.submit(m.id, url.trim()))}>
              {busy === "submit" && <span className="spinner" />} Submit
            </button>
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted">
            Demo repos:
            {DEMO_REPOS.map((d) => (
              <button key={d.label} className="rounded-full border border-line bg-white px-2 py-0.5 hover:border-brand"
                onClick={() => setUrl(d.url)}>{d.label}</button>
            ))}
          </div>
        </div>
      )}
      {!canSubmit && job.status === "FUNDED" && (m.status === "PENDING" || m.status === "NEEDS_REVIEW") && (
        <p className="mt-3 text-sm text-muted">Waiting for the freelancer to {m.status === "PENDING" ? "submit" : "resubmit"}.</p>
      )}

      {canVerify && (
        <div className="mt-4">
          <button className="btn-primary" disabled={busy !== null} onClick={() => run("verify", () => api.verify(m.id))}>
            {busy === "verify" && <span className="spinner" />} Run AI verification
          </button>
          {busy === "verify" && (
            <p className="mt-2 text-sm text-muted">{phase} <span className="font-mono">{elapsed}s</span> <span className="text-xs">(usually 10 to 30 s; instant if this exact commit was checked before)</span></p>
          )}
        </div>
      )}

      {release && <ReleaseStrip release={release} currency={job.currency} onChange={onChange} />}
      {error && <p className="mt-3 rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">{error}</p>}
      {report && busy !== "verify" && (
        <details className="mt-2" open={m.status !== "PAID"}>
          <summary className="cursor-pointer text-sm font-medium text-brand">Latest AI verification report</summary>
          <VerificationReportView report={report} currency={job.currency} />
        </details>
      )}
    </article>
  );
}

function ReleaseStrip({ release: r, currency, onChange }: { release: LedgerRow; currency: string; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  const steps = [
    { label: "Gate approved", done: true },
    { label: "Captured", done: ["CAPTURED", "PAYOUT_PENDING", "PAID", "PAYOUT_FAILED"].includes(r.status) },
    { label: "Payout sent", done: ["PAYOUT_PENDING", "PAID"].includes(r.status) },
    { label: "Paid", done: r.status === "PAID" },
  ];
  return (
    <div className="mt-4 rounded-lg border border-line p-4">
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className="font-semibold">Release {money(r.amount, currency)}</span>
        <StatusBadge status={r.status} />
        {r.finalCapture && <span className="text-xs text-muted">final capture</span>}
      </div>
      <ol className="mt-3 flex flex-wrap gap-2">
        {steps.map((s) => (
          <li key={s.label} className={`rounded-full px-3 py-1 text-xs font-medium ${s.done ? "bg-ok-soft text-ok" : "bg-slate-100 text-slate-500"}`}>
            {s.done ? "✓ " : ""}{s.label}
          </li>
        ))}
      </ol>
      <div className="mt-2 grid gap-1 font-mono text-xs text-muted sm:grid-cols-2">
        {r.paypalCaptureId && <span>capture {r.paypalCaptureId}</span>}
        {r.paypalPayoutBatchId && <span>payout batch {r.paypalPayoutBatchId}</span>}
      </div>
      {r.failureReason && <p className="mt-2 text-sm text-bad">{r.failureReason}</p>}
      {r.status === "PAYOUT_FAILED" && (
        <button className="btn-ghost mt-3" disabled={busy}
          onClick={async () => { setBusy(true); try { await api.retryPayout(r.releaseId); } finally { setBusy(false); onChange(); } }}>
          Retry payout
        </button>
      )}
    </div>
  );
}
