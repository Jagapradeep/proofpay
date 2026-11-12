"use client";

import type { CellClickedEvent, ColDef, ICellRendererParams, IRowNode } from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GridToolbar, gridTheme } from "@/components/grid";
import { StatusBadge } from "@/components/StatusBadge";
import { api } from "@/lib/api";
import { label, money, when } from "@/lib/format";
import type { LedgerRow } from "@/lib/types";

const PAYPAL_SANDBOX_ACTIVITY = "https://www.sandbox.paypal.com/myaccount/transactions";

type View = "ALL" | "IN_FLIGHT" | "PAID" | "FAILED";
const IN_FLIGHT = ["VERIFIED", "CAPTURE_PENDING", "CAPTURED", "PAYOUT_PENDING"];
const MATCH: Record<View, (s: string) => boolean> = {
  ALL: () => true,
  IN_FLIGHT: (s) => IN_FLIGHT.includes(s),
  PAID: (s) => s === "PAID",
  FAILED: (s) => s.endsWith("FAILED"),
};

export default function LedgerPage() {
  const [rows, setRows] = useState<LedgerRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [view, setView] = useState<View>("ALL");
  const [shown, setShown] = useState({ count: 0, amount: 0 });
  const grid = useRef<AgGridReact<LedgerRow>>(null);
  const viewRef = useRef<View>("ALL");

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

  const pickView = (v: View) => {
    viewRef.current = v;
    setView(v);
    grid.current?.api.onFilterChanged();
  };

  // Click an id cell to copy it; the cell flashes as confirmation.
  const copyCell = useCallback((e: CellClickedEvent<LedgerRow>) => {
    if (!e.value || e.node.rowPinned) return;
    navigator.clipboard?.writeText(String(e.value)).catch(() => {});
    e.api.flashCells({ rowNodes: [e.node], columns: [e.column] });
  }, []);

  // The Failure column only appears when something has failed.
  const anyFailed = !!rows?.some((x) => x.status.endsWith("FAILED"));

  const columns = useMemo<ColDef<LedgerRow>[]>(() => {
    const id = (headerName: string, field: keyof LedgerRow, width: number): ColDef<LedgerRow> => ({
      headerName, field, width, cellClass: "font-mono text-xs cursor-copy", onCellClicked: copyCell,
      headerTooltip: "Real PayPal sandbox id. Click a cell to copy it.",
    });
    return [
      {
        headerName: "Job", field: "jobTitle", flex: 1.4, minWidth: 190, filter: true, pinned: "left",
        cellRenderer: (p: ICellRendererParams<LedgerRow>) =>
          !p.data ? null : p.node.rowPinned
            ? <span className="font-semibold">{p.value}</span>
            : <Link className="font-medium text-brand hover:underline" href={`/jobs/${p.data.jobId}`}>{p.value}</Link>,
      },
      {
        headerName: "Milestone", flex: 1.2, minWidth: 170, filter: true,
        valueGetter: (p) => (p.data && !p.node?.rowPinned ? `${p.data.milestoneSeq}. ${p.data.milestoneTitle}` : ""),
      },
      {
        headerName: "Amount", field: "amount", width: 120, filter: "agNumberColumnFilter",
        cellClass: "font-semibold tabular-nums", valueFormatter: (p) => money(p.value, p.data?.currency),
      },
      {
        headerName: "Status", field: "status", width: 160, filter: true,
        // Search and filter on the words shown in the badge ("Payout pending"), not the raw code.
        filterValueGetter: (p) => (p.data ? label(p.data.status) : ""),
        getQuickFilterText: (p) => (p.value ? label(p.value) : ""),
        cellRenderer: (p: ICellRendererParams<LedgerRow>) =>
          p.value && !p.node.rowPinned ? <StatusBadge status={p.value} /> : null,
      },
      { headerName: "Payee", field: "payeeEmail", flex: 1, minWidth: 200, filter: true },
      id("Capture id", "paypalCaptureId", 180),
      id("Payout batch", "paypalPayoutBatchId", 160),
      id("Authorization", "paypalAuthorizationId", 180),
      {
        headerName: "Final", field: "finalCapture", width: 90, cellClass: "text-ok font-semibold",
        headerTooltip: "The last capture on the authorization (releases the rest of the hold)",
        valueFormatter: (p) => (p.value ? "✓ final" : ""),
      },
      { headerName: "Failure", field: "failureReason", width: 200, hide: !anyFailed, tooltipField: "failureReason", cellClass: "text-bad" },
      {
        headerName: "Updated", field: "updatedAt", width: 170, sort: "desc",
        valueFormatter: (p) => (p.node?.rowPinned ? "" : when(p.value)),
      },
    ];
  }, [copyCell, anyFailed]);

  const totals = useMemo(() => {
    const r = rows ?? [];
    const sum = (f: (x: LedgerRow) => boolean) => r.filter(f).reduce((s, x) => s + x.amount, 0);
    const count = (v: View) => r.filter((x) => MATCH[v](x.status)).length;
    return {
      released: sum((x) => ["CAPTURED", "PAYOUT_PENDING", "PAID", "PAYOUT_FAILED"].includes(x.status)),
      paid: sum((x) => x.status === "PAID"),
      all: r.length, inFlight: count("IN_FLIGHT"), paidCount: count("PAID"), failed: count("FAILED"),
    };
  }, [rows]);

  // Count and sum of whatever the filters currently show (displayed in the toolbar).
  const onModelUpdated = useCallback(() => {
    let count = 0;
    let amount = 0;
    grid.current?.api.forEachNodeAfterFilter((n: IRowNode<LedgerRow>) => {
      if (n.data) { count++; amount += n.data.amount; }
    });
    setShown((s) => (s.count === count && s.amount === amount ? s : { count, amount }));
  }, []);

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
          {rows ? "Lost contact with the server; retrying…" : "Waiting for the server… retrying."}
        </p>
      )}
      <div className="card overflow-hidden">
        <GridToolbar<View>
          search={search}
          onSearch={setSearch}
          placeholder="Search jobs, payees, ids…"
          active={view}
          onChip={pickView}
          chips={[
            { id: "ALL", label: "All", count: totals.all },
            { id: "IN_FLIGHT", label: "In flight", count: totals.inFlight },
            { id: "PAID", label: "Paid", count: totals.paidCount },
            { id: "FAILED", label: "Failed", count: totals.failed },
          ]}
        >
          <span className="text-sm text-muted">
            <b className="text-base font-semibold tabular-nums text-ink">{money(shown.amount, rows?.[0]?.currency)}</b>
            {" "}across {shown.count} release{shown.count === 1 ? "" : "s"}
          </span>
          <span className="flex items-center gap-1.5 text-xs text-muted" title="Refreshes every 5 seconds"><span className="live-dot" /> Live</span>
          <button
            className="btn-ghost !px-3 !py-1.5 !text-xs"
            disabled={!rows?.length}
            onClick={() => grid.current?.api.exportDataAsCsv({ fileName: "proofpay-ledger.csv" })}
          >
            Export CSV
          </button>
        </GridToolbar>
        <div>
          <AgGridReact<LedgerRow>
            ref={grid}
            theme={gridTheme}
            rowData={rows ?? undefined}
            columnDefs={columns}
            getRowId={(p) => p.data.releaseId}
            defaultColDef={{ sortable: true, resizable: true, enableCellChangeFlash: true }}
            quickFilterText={search}
            isExternalFilterPresent={() => viewRef.current !== "ALL"}
            doesExternalFilterPass={(n) => !!n.data && MATCH[viewRef.current](n.data.status)}
            onModelUpdated={onModelUpdated}
            rowClassRules={{ "row-bad": (p) => !!p.data?.status?.endsWith("FAILED") }}
            domLayout="autoHeight"
            rowSelection={{ mode: "singleRow", checkboxes: false, enableClickSelection: true }}
            suppressCellFocus
            suppressMultiSort
            enableCellTextSelection
            pagination={(rows?.length ?? 0) > 20}
            paginationPageSize={20}
            paginationPageSizeSelector={[10, 20, 50]}
            tooltipShowDelay={300}
            animateRows
            overlayNoRowsTemplate="No releases yet. They appear here when the policy gate approves a milestone."
            loading={rows === null}
          />
        </div>
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: string; tone?: "ok" | "bad" }) {
  const bar = tone === "ok" ? "bg-ok" : tone === "bad" ? "bg-bad" : "bg-brand";
  return (
    <div className="card relative overflow-hidden p-4 pl-5">
      <span className={`absolute inset-y-0 left-0 w-1 ${bar}`} />
      <div className="text-xs font-medium uppercase tracking-wide text-muted">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${tone === "ok" ? "text-ok" : tone === "bad" ? "text-bad" : ""}`}>{value}</div>
    </div>
  );
}
