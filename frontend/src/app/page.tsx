"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { money, when } from "@/lib/format";
import type { Job } from "@/lib/types";

const STEPS = [
  { n: 1, title: "Post a job", text: "Plain English. AI turns it into testable acceptance criteria that both sides accept." },
  { n: 2, title: "Fund with PayPal", text: "The money is authorized (held), not charged. Nobody can touch it yet." },
  { n: 3, title: "Submit the work", text: "The freelancer submits a GitHub repo. An AI agent checks every criterion and quotes evidence." },
  { n: 4, title: "Code decides", text: "A deterministic policy gate, not the LLM, approves a partial capture and a PayPal payout." },
];

export default function JobsPage() {
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.jobs()
      .then((j) => setJobs([...j].sort((a, b) => b.createdAt.localeCompare(a.createdAt))))
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <div className="space-y-10">
      <section className="grid gap-6 md:grid-cols-[1.2fr_1fr] md:items-center">
        <div>
          <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">Escrow that pays on proof.</h1>
          <p className="mt-3 max-w-xl text-muted">
            PayPal holds the client&apos;s money. An AI verifier checks the freelancer&apos;s work against the agreed
            criteria. A policy gate written in plain code decides whether money moves: <b className="text-ink">the LLM
            proposes, code disposes.</b>
          </p>
          <div className="mt-5 flex flex-wrap gap-3">
            <Link href="/jobs/new" className="btn-primary">Post a job</Link>
            <Link href="/ledger" className="btn-ghost">Open the ledger</Link>
          </div>
        </div>
        <ol className="grid gap-3 sm:grid-cols-2">
          {STEPS.map((s) => (
            <li key={s.n} className="card p-4">
              <div className="flex items-center gap-2">
                <span className="grid h-6 w-6 place-items-center rounded-full bg-brand-soft text-xs font-bold text-brand">
                  {s.n}
                </span>
                <span className="font-semibold">{s.title}</span>
              </div>
              <p className="mt-2 text-sm text-muted">{s.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold">Jobs</h2>
          {jobs && <span className="text-sm text-muted">{jobs.length} total</span>}
        </div>
        {error && <p className="card p-4 text-sm text-bad">Could not load jobs: {error}</p>}
        {!jobs && !error && <p className="text-sm text-muted">Loading…</p>}
        {jobs?.length === 0 && (
          <div className="card p-8 text-center text-muted">
            No jobs yet. <Link href="/jobs/new" className="font-medium text-brand">Post the first one</Link>.
          </div>
        )}
        <ul className="grid gap-3">
          {jobs?.map((j) => {
            const captured = j.funding?.capturedAmount ?? 0;
            const pct = j.totalAmount ? Math.round((captured / j.totalAmount) * 100) : 0;
            return (
              <li key={j.id}>
                <Link href={`/jobs/${j.id}`} className="card block p-4 transition hover:border-brand">
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                    <span className="font-semibold">{j.title}</span>
                    <StatusBadge status={j.status} />
                    <span className="ml-auto text-sm font-medium">{money(j.totalAmount, j.currency)}</span>
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted">
                    <span>{j.milestones.length} milestones</span>
                    <span>Released {money(captured, j.currency)}</span>
                    <span>Created {when(j.createdAt)}</span>
                  </div>
                  <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100">
                    <div className="h-full rounded-full bg-ok" style={{ width: `${pct}%` }} />
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}
