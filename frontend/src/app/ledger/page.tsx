"use client";

import { AllCommunityModule, ModuleRegistry, themeQuartz, type ColDef, type ICellRendererParams } from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { StatusBadge } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { money, when } from "@/lib/format";
import type { LedgerRow } from "@/lib/types";

ModuleRegistry.registerModules([AllCommunityModule]);

const theme = themeQuartz.withParams({
  accentColor: "#1e40af",
  borderColor: "#e2e8f0",
  headerBackgroundColor: "#f8fafc",
  fontSize: 13,
});

const PAYPAL_SANDBOX_ACTIVITY = "https://www.sandbox.paypal.com/myaccount/transactions";

export default function LedgerPage() {
  const [rows, setRows] = useState<LedgerRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let inFlight = false;
    let stopped = false;
    const load = async () => {
      if (inFlight) return; // never let polls overlap (a slow response could overwrite a newer one)
      inFlight = true;
      try {
        const r = await api.ledger();
        if (!stopped) { setRows(r); setError(null); }
      } catch (e) {
        if (!stopped) setError((e as Error).message);
      } finally {
        inFlight = false;
      }
    };
    load();
    const t = setInterval(load, 5000);
    return () => { stopped = true; clearInterval(t); };
  }, []);

  const columns = useMemo<ColDef<LedgerRow>[]>(() => [
    {
      headerName: "Job", field: "jobTitle", flex: 1.4, minWidth: 180, filter: true,
      cellRenderer: (p: ICellRendererParams<LedgerRow>) =>
        p.data ? <Link className="font-medium text-brand hover:underline" href={`/jobs/${p.data.jobId}`}>{p.value}</Link> : null,
    },
    { headerName: "Milestone", valueGetter: (p) => p.data && `${p.data.milestoneSeq}. ${p.data.milestoneTitle}`, flex: 1.2, minWidth: 170 },
    {
      headerName: "Amount", field: "amount", width: 110, type: "rightAligned", filter: "agNumberColumnFilter",
      valueFormatter: (p) => money(p.value, p.data?.currency),
    },
    {
      headerName: "Status", field: "status", width: 150, filter: true,
      cellRenderer: (p: ICellRendererParams<LedgerRow>) => (p.value ? <StatusBadge status={p.value} /> : null),
    },
    { headerName: "Payee", field: "payeeEmail", flex: 1, minWidth: 200 },
    { headerName: "Capture id", field: "paypalCaptureId", width: 180, cellClass: "font-mono" },
    { headerName: "Payout batch", field: "paypalPayoutBatchId", width: 160, cellClass: "font-mono" },
    { headerName: "Final", field: "finalCapture", width: 80, valueFormatter: (p) => (p.value ? "yes" : "") },
    { headerName: "Authorization", field: "paypalAuthorizationId", width: 180, cellClass: "font-mono", hide: false },
    { headerName: "Failure", field: "failureReason", width: 180 },
    { headerName: "Updated", field: "updatedAt", width: 160, sort: "desc", valueFormatter: (p) => when(p.value) },
  ], []);

  const totals = useMemo(() => {
    const r = rows ?? [];
    const sum = (f: (x: LedgerRow) => boolean) => r.filter(f).reduce((s, x) => s + x.amount, 0);
    return {
      released: sum((x) => x.status !== "CAPTURE_FAILED"),
      paid: sum((x) => x.status === "PAID"),
      inFlight: r.filter((x) => ["VERIFIED", "CAPTURE_PENDING", "CAPTURED", "PAYOUT_PENDING"].includes(x.status)).length,
      failed: r.filter((x) => x.status.endsWith("FAILED")).length,
    };
  }, [rows]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Ledger</h1>
          <p className="mt-1 text-sm text-muted">
            One row per release: a partial capture on the client&apos;s PayPal authorization, then a payout to the freelancer.
            Ids are the real PayPal sandbox capture and payout ids.
          </p>
        </div>
        <a href={PAYPAL_SANDBOX_ACTIVITY} target="_blank" rel="noreferrer" className="btn-ghost ml-auto">Sandbox activity ↗</a>
      </div>

      <div className="grid gap-3 sm:grid-cols-4">
        <Tile label="Captured from holds" value={money(totals.released)} />
        <Tile label="Paid out to freelancers" value={money(totals.paid)} tone="ok" />
        <Tile label="In flight" value={String(totals.inFlight)} />
        <Tile label="Failed (needs action)" value={String(totals.failed)} tone={totals.failed ? "bad" : undefined} />
      </div>

      {error && (
        <p className="card flex items-center gap-3 p-4 text-sm text-muted">
          <span className="spinner text-brand" />
          {rows ? "Lost contact with the server; retrying…" : "Waiting for the server to wake up (it sleeps when idle)… retrying."}
        </p>
      )}
      <div className="card overflow-hidden" style={{ height: 520 }}>
        <AgGridReact<LedgerRow>
          theme={theme}
          rowData={rows ?? undefined}
          columnDefs={columns}
          getRowId={(p) => p.data.releaseId}
          defaultColDef={{ sortable: true, resizable: true }}
          overlayNoRowsTemplate="No releases yet. They appear here when the policy gate approves a milestone."
          loading={rows === null}
        />
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: string; tone?: "ok" | "bad" }) {
  return (
    <div className="card p-4">
      <div className="text-xs text-muted">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${tone === "ok" ? "text-ok" : tone === "bad" ? "text-bad" : ""}`}>{value}</div>
    </div>
  );
}
