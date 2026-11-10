"use client";

import { useState } from "react";
import { api, parseJson } from "@/lib/api";
import { when } from "@/lib/format";
import type { Criterion, Job } from "@/lib/types";
import { useApp } from "./Providers";

export function CriteriaPanel({ job, onChange }: { job: Job; onChange: () => void }) {
  const { role } = useApp();
  const [busy, setBusy] = useState<"compile" | "accept" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const compiled = job.milestones.every((m) => m.criteriaJson);
  const editable = job.status === "DRAFT";
  const myAcceptance = role === "CLIENT" ? job.clientAcceptedCriteriaAt : job.freelancerAcceptedCriteriaAt;

  const run = async (kind: "compile" | "accept", fn: () => Promise<unknown>) => {
    setBusy(kind);
    setError(null);
    try {
      await fn();
      onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card p-5">
      <div className="flex flex-wrap items-center gap-3">
        <div>
          <h2 className="section-title">Step 1 · Acceptance criteria</h2>
          <p className="mt-1 text-sm text-muted">
            Written by the AI from the job text. They become the contract: the verifier checks exactly these.
          </p>
        </div>
        {editable && role === "CLIENT" && (
          <button className={compiled ? "btn-ghost ml-auto" : "btn-primary ml-auto"} disabled={busy !== null}
            onClick={() => run("compile", () => api.compileCriteria(job.id))}>
            {busy === "compile" && <span className="spinner" />}
            {compiled ? "Regenerate with AI" : "Generate criteria with AI"}
          </button>
        )}
      </div>

      {busy === "compile" && <p className="mt-4 text-sm text-muted">The criteria compiler is reading the job description (about 10 s)…</p>}
      {!compiled && busy !== "compile" && (
        <p className="mt-4 rounded-lg bg-slate-50 p-4 text-sm text-muted">
          {role === "CLIENT" ? "No criteria yet. Generate them to continue." : "Waiting for the client to generate criteria."}
        </p>
      )}

      {compiled && (
        <div className="mt-4 grid gap-4 md:grid-cols-2">
          {job.milestones.map((m) => (
            <div key={m.id} className="rounded-lg border border-line p-4">
              <div className="mb-2 text-sm font-semibold">Milestone {m.seq}: {m.title}</div>
              <ul className="space-y-2">
                {(parseJson<Criterion[]>(m.criteriaJson) ?? []).map((c) => (
                  <li key={c.id} className="text-sm">
                    <span className="mr-2 rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-slate-600">{c.id}</span>
                    {c.description}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}

      {compiled && (
        <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-4 text-sm">
          <Acceptance who="Client" at={job.clientAcceptedCriteriaAt} />
          <Acceptance who="Freelancer" at={job.freelancerAcceptedCriteriaAt} />
          {editable && !myAcceptance && (
            <button className="btn-primary ml-auto" disabled={busy !== null}
              onClick={() => run("accept", () => api.acceptCriteria(job.id, role))}>
              {busy === "accept" && <span className="spinner" />}
              Accept as {role === "CLIENT" ? "client" : "freelancer"}
            </button>
          )}
          {!editable && <span className="ml-auto text-xs text-muted">Locked once funding started.</span>}
        </div>
      )}
      {error && <p className="mt-3 rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">{error}</p>}
    </section>
  );
}

function Acceptance({ who, at }: { who: string; at: string | null }) {
  return at ? (
    <span className="rounded-full bg-ok-soft px-3 py-1 text-xs font-medium text-ok">✓ {who} accepted · {when(at)}</span>
  ) : (
    <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">{who}: not yet</span>
  );
}
