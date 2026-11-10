import { label, money, shortSha, when } from "@/lib/format";
import type { VerificationReport } from "@/lib/types";

/** The verifier agent's report, plus what the code (scanner, evidence check, gate) did with it. */
export function VerificationReportView({ report, currency }: { report: VerificationReport; currency: string }) {
  const released = report.outcome === "RELEASED";
  const commitUrl = `${report.repoUrl.replace(/\/$/, "").replace(/\.git$/, "")}/tree/${report.commitSha}`;

  return (
    <div className="mt-4 space-y-4">
      <div className={`rounded-lg border p-4 ${released ? "border-green-200 bg-ok-soft" : "border-red-200 bg-bad-soft"}`}>
        <div className="flex flex-wrap items-center gap-3">
          <span className={`text-lg font-bold ${released ? "text-ok" : "text-bad"}`}>
            {released ? "✓ Gate approved: payment released" : report.injectionFlagged ? "⛔ Blocked: prompt injection" : "⛔ Blocked: sent to human review"}
          </span>
          <span className="ml-auto text-xs text-slate-600">
            {report.model} · {(report.durationMs / 1000).toFixed(0)} s · {when(report.verifiedAt)}
          </span>
        </div>
        <div className="mt-2 grid gap-2 text-sm sm:grid-cols-3">
          <span>Agent proposed: <b>{money(report.proposedAmount, currency)}</b></span>
          <span>
            Confidence: <b>{fmtConf(report.effectiveConfidence)}</b>
            {report.agentConfidence !== report.effectiveConfidence && report.agentConfidence !== null && (
              <span className="text-slate-600"> (agent said {fmtConf(report.agentConfidence)})</span>
            )}
          </span>
          <span>
            Commit <a href={commitUrl} target="_blank" rel="noreferrer" className="font-mono underline">{shortSha(report.commitSha)}</a>
          </span>
        </div>
        {report.gateReasons.length > 0 && (
          <ul className="mt-3 space-y-1 text-sm text-bad">
            {report.gateReasons.map((r) => <li key={r}>• {r}</li>)}
          </ul>
        )}
      </div>

      {report.scannerFindings.length > 0 && (
        <div className="rounded-lg border border-red-200 bg-white">
          <div className="border-b border-red-100 bg-bad-soft px-4 py-2 text-sm font-semibold text-bad">
            Injection scanner (plain regex, not AI) found {report.scannerFindings.length} suspicious line(s)
            {report.agentInjectionSuspected && " · the AI agent flagged it too"}
          </div>
          <ul className="divide-y divide-line">
            {report.scannerFindings.map((f, i) => (
              <li key={i} className="px-4 py-2 text-sm">
                <span className="mr-2 rounded bg-bad-soft px-1.5 py-0.5 font-mono text-xs text-bad">{label(f.rule)}</span>
                <span className="font-mono text-xs text-muted">{f.path}:{f.line}</span>
                <div className="mt-1 font-mono text-xs text-ink">&ldquo;{f.snippet}&rdquo;</div>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="rounded-lg border border-line">
        <div className="border-b border-line px-4 py-2 text-sm font-semibold">Criteria checked by the agent</div>
        <ul className="divide-y divide-line">
          {report.criteria.map((c) => (
            <li key={c.criterionId} className="px-4 py-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className={`font-bold ${c.proven ? "text-ok" : "text-bad"}`}>{c.proven ? "✓" : "✗"}</span>
                <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-slate-600">{c.criterionId}</span>
                <span>{c.note}</span>
                {c.passed && !c.proven && <span className="text-xs text-warn">(claimed passed, but no verified evidence)</span>}
              </div>
              {c.evidence.length > 0 && (
                <ul className="mt-2 space-y-1 pl-6">
                  {c.evidence.map((e, i) => (
                    <li key={i} className="text-xs">
                      <span className={e.verified ? "text-ok" : "text-bad"} title={e.verified ? "Found word for word in the file" : "Not found in the file"}>
                        {e.verified ? "quote verified" : "quote NOT found"}
                      </span>
                      <span className="mx-1 font-mono text-muted">{e.path}</span>
                      <code className="rounded bg-slate-50 px-1 py-0.5">{e.quote}</code>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      </div>

      {report.adjustments.length > 0 && (
        <div className="rounded-lg bg-warn-soft px-4 py-3 text-sm text-warn">
          <b>Code overrode the agent:</b>
          <ul className="mt-1">{report.adjustments.map((a) => <li key={a}>• {a}</li>)}</ul>
        </div>
      )}

      {report.agentSummary && (
        <p className="text-sm text-muted"><b className="text-ink">Agent summary:</b> {report.agentSummary}</p>
      )}
      <p className="text-xs text-muted">
        Agent read {report.filesRead.length} of {report.filesFetched} fetched files: {report.filesRead.join(", ") || "none"}
      </p>
    </div>
  );
}

function fmtConf(c: number | null): string {
  return c === null ? "—" : c.toFixed(2);
}
