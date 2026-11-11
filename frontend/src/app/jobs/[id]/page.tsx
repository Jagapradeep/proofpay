"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { AuditTimeline } from "@/components/AuditTimeline";
import { CriteriaPanel } from "@/components/CriteriaPanel";
import { FundingPanel } from "@/components/FundingPanel";
import { MilestoneCard } from "@/components/MilestoneCard";
import { StatusBadge } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import type { AuditEvent, Job, LedgerRow } from "@/lib/types";

const IN_FLIGHT = new Set(["VERIFIED", "CAPTURE_PENDING", "CAPTURED", "PAYOUT_PENDING"]);

export default function JobPage() {
  const { id } = useParams<{ id: string }>();
  const [job, setJob] = useState<Job | null>(null);
  const [releases, setReleases] = useState<LedgerRow[]>([]);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const lastNudge = useRef(0);
  const latestRequest = useRef(0);
  const loading = useRef(0);

  const load = useCallback(async () => {
    // Background polls and action refreshes can overlap. Only the most recently started load may update the
    // page, so a slow, older response can never put stale state (e.g. an old report) back on screen.
    const requestId = ++latestRequest.current;
    loading.current++;
    try {
      const [j, ledger, events] = await Promise.all([api.job(id), api.ledger(), api.audit(id)]);
      if (requestId !== latestRequest.current) return;
      const mine = ledger.filter((r) => r.jobId === id);
      setJob(j);
      setReleases(mine);
      setAudit(events);
      setError(null);
      // Payouts settle asynchronously; ask the backend to re-check PayPal now and then (it also polls itself).
      const pending = mine.filter((r) => IN_FLIGHT.has(r.status));
      if (pending.length && Date.now() - lastNudge.current > 8000) {
        lastNudge.current = Date.now();
        await Promise.all(pending.map((r) => api.refreshRelease(r.releaseId).catch(() => undefined)));
      }
    } catch (e) {
      if (requestId === latestRequest.current) setError((e as Error).message);
    } finally {
      loading.current--;
    }
  }, [id]);

  useEffect(() => {
    load();
    // Background refresh; skipped while another load is still running (slow or waking server).
    const t = setInterval(() => {
      if (loading.current === 0) load();
    }, 4000);
    return () => clearInterval(t);
  }, [load]);

  if (!job) return <WaitingForServer error={error} what="job" />;

  return (
    <div className="space-y-6">
      <div>
        <Link href="/" className="text-sm text-muted hover:text-ink">← All jobs</Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight">{job.title}</h1>
          <StatusBadge status={job.status} />
          <span className="ml-auto text-xl font-semibold">{money(job.totalAmount, job.currency)}</span>
        </div>
        <p className="mt-2 max-w-3xl text-sm text-muted">{job.description}</p>
        <div className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted">
          <span>Client: <span className="font-mono">{job.clientEmail}</span></span>
          <span>Freelancer: <span className="font-mono">{job.freelancerEmail}</span></span>
        </div>
      </div>

      <CriteriaPanel job={job} onChange={load} />
      <FundingPanel job={job} onChange={load} />

      <section className="space-y-4">
        <div>
          <h2 className="section-title">Step 3 · Milestones</h2>
          <p className="mt-1 text-sm text-muted">
            The freelancer submits a repo, the AI verifier proposes a release, and the policy gate decides.
          </p>
        </div>
        {job.status !== "FUNDED" && job.status !== "COMPLETED" && (
          <p className="card p-4 text-sm text-muted">Milestones open once the job is funded.</p>
        )}
        {job.milestones.map((m) => (
          <MilestoneCard key={m.id} job={job} milestone={m} onChange={load}
            release={releases.find((r) => r.milestoneSeq === m.seq)} />
        ))}
      </section>

      {job.status === "COMPLETED" && (
        <div className="card border-green-200 bg-ok-soft p-5 text-ok">
          <b>Job complete.</b> Every milestone was verified, captured from the PayPal hold and paid out to the freelancer.
        </div>
      )}

      <AuditTimeline events={audit} />
      {error && <p className="text-xs text-warn">Refresh failed: {error} (retrying)</p>}
    </div>
  );
}

/** First load: a sleeping Render instance can take ~1 min to wake, so keep retrying instead of failing. */
function WaitingForServer({ error, what }: { error: string | null; what: string }) {
  if (error && /not found/i.test(error)) {
    return <p className="card p-6 text-bad">{error}</p>;
  }
  return (
    <div className="card flex items-center gap-3 p-6 text-sm text-muted">
      <span className="spinner text-brand" />
      {error ? `Waiting for the server to wake up (it sleeps when idle)… retrying.` : `Loading the ${what}…`}
    </div>
  );
}
