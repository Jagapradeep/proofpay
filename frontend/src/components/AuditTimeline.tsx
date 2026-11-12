"use client";

import type { ColDef, ICellRendererParams } from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { GridToolbar, gridTheme } from "@/components/grid";
import { label, money, shortSha, when } from "@/lib/format";
import type { AuditEvent } from "@/lib/types";

const ACTOR: Record<string, string> = {
  AGENT: "bg-violet-100 text-violet-700",
  POLICY_GATE: "bg-ink text-white",
  PAYPAL: "bg-brand-soft text-brand",
  CLIENT: "bg-amber-100 text-amber-800",
  FREELANCER: "bg-teal-100 text-teal-800",
  SYSTEM: "bg-slate-100 text-slate-600",
};

const GOOD = new Set(["GATE_APPROVED", "PAYOUT_SUCCEEDED", "JOB_COMPLETED", "FUNDS_AUTHORIZED"]);
const BAD = new Set(["GATE_BLOCKED"]);

function pretty(json: string | null): string {
  if (!json) return "";
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

const MONEY_KEYS = new Set(["amount", "total"]);

/** One field as plain words: "authorizationId" -> "Authorization id: 1TU…". */
function field(key: string, v: unknown): string {
  const name = label(key.replace(/([a-z])([A-Z])/g, "$1_$2"));
  let value = String(v);
  if (MONEY_KEYS.has(key) && typeof v === "number") value = money(v);
  else if (typeof v === "boolean") value = v ? "yes" : "no";
  else if (key === "commit") value = shortSha(value);
  else if (key.endsWith("At")) value = when(value);
  else if (/status$/i.test(key)) value = label(value);
  else value = value.replace(/^https:\/\/github\.com\//, "");
  return `${name}: ${value}`;
}

/** The event's stored JSON as one readable sentence; the full record is one click away. */
function describe(e: AuditEvent): string {
  if (!e.detail) return "";
  let d: Record<string, unknown>;
  try {
    const parsed = JSON.parse(e.detail);
    if (!parsed || typeof parsed !== "object") return String(parsed);
    d = parsed;
  } catch {
    return e.detail;
  }
  const reasons = Array.isArray(d.reasons) ? d.reasons.map(String) : [];
  switch (e.type) {
    case "GATE_BLOCKED":
      return `Payment refused: ${reasons.join("; ")}`;
    case "RELEASE_PROPOSED":
      return `Proposed ${money(Number(d.amount ?? 0))}` + (reasons.length ? `. Concerns: ${reasons.join("; ")}` : "");
    case "AGENT_VERDICT":
      if (typeof d.summary === "string") return d.summary;
      break;
    case "INJECTION_SCAN": {
      const n = Array.isArray(d.findings) ? d.findings.length : 0;
      return d.flagged
        ? `Flagged ${n} suspicious passage${n === 1 ? "" : "s"} written to steer the AI reviewer`
        : "Clean: no text trying to steer the AI reviewer";
    }
    case "CRITERIA_COMPILED": {
      const c = d.criteria && typeof d.criteria === "object" ? Object.values(d.criteria) : [];
      const n = c.reduce((sum: number, x) => sum + (Array.isArray(x) ? x.length : 0), 0);
      return `${n} acceptance criteria written by ${d.model ?? "the AI"}${d.cached ? " (reused from cache)" : ""}`;
    }
  }
  return Object.entries(d)
    .filter(([, v]) => ["string", "number", "boolean"].includes(typeof v))
    .slice(0, 4)
    .map(([k, v]) => field(k, v))
    .join(" · ");
}

/** Every step, who did it, and why. The gate and PayPal rows show that the LLM never moves money itself. */
export function AuditTimeline({ events }: { events: AuditEvent[] }) {
  const [search, setSearch] = useState("");
  const [actor, setActor] = useState("ALL");
  const [openId, setOpenId] = useState<number | null>(null);
  const grid = useRef<AgGridReact<AuditEvent>>(null);
  const actorRef = useRef("ALL");

  const chips = useMemo(() => {
    const counts = new Map<string, number>();
    for (const e of events) counts.set(e.actor, (counts.get(e.actor) ?? 0) + 1);
    return [
      { id: "ALL", label: "All", count: events.length },
      ...[...counts].map(([id, count]) => ({ id, label: label(id), count })),
    ];
  }, [events]);

  // Kept in a ref so the column definitions stay stable while the page polls (no lost sort, filter or widths).
  const step = useRef(new Map<number, number>());
  step.current = new Map(events.map((e, i) => [e.id, i + 1]));

  const columns = useMemo<ColDef<AuditEvent>[]>(() => [
    // Step number in the order things happened; it stays with its event when the grid is sorted or filtered.
    { headerName: "Step", width: 84, valueGetter: (p) => (p.data ? step.current.get(p.data.id) : null), cellClass: "text-muted tabular-nums" },
    { headerName: "Time", field: "createdAt", width: 170, cellClass: "font-mono text-xs", valueFormatter: (p) => when(p.value) },
    {
      headerName: "Who acted", field: "actor", width: 140, filter: true,
      filterValueGetter: (p) => (p.data ? label(p.data.actor) : ""),
      getQuickFilterText: (p) => (p.value ? label(p.value) : ""),
      cellRenderer: (p: ICellRendererParams<AuditEvent>) => (
        <span className={`inline-block w-24 truncate rounded px-2 py-0.5 text-center text-xs font-semibold leading-5 ${ACTOR[p.value] ?? ACTOR.SYSTEM}`}>
          {label(p.value)}
        </span>
      ),
    },
    {
      headerName: "Event", field: "type", width: 230, filter: true, valueFormatter: (p) => label(p.value), cellClass: "font-medium",
      filterValueGetter: (p) => (p.data ? label(p.data.type) : ""),
      getQuickFilterText: (p) => (p.value ? label(p.value) : ""),
    },
    {
      headerName: "What happened", flex: 1, minWidth: 260, wrapText: true, autoHeight: true, sortable: false,
      valueGetter: (p) => (p.data ? describe(p.data) : ""),
      // Padding and line height live inside the cell so auto-height measures them.
      cellRenderer: (p: ICellRendererParams<AuditEvent>) => (
        <div className={`py-3 text-xs leading-5 ${p.data && BAD.has(p.data.type) ? "text-bad" : "text-muted"}`}>{p.value}</div>
      ),
    },
  ], []);

  const open = events.find((e) => e.id === openId) ?? null;
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (openId !== null) panel.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [openId]);
  const close = () => { grid.current?.api.deselectAll(); setOpenId(null); };

  return (
    <section className="card overflow-hidden">
      <div className="p-5 pb-3">
        <h2 className="section-title">Audit trail</h2>
        <p className="mt-1 text-sm text-muted">
          Append-only. Each row says who acted: the AI agent, the policy gate (code), PayPal, or a person. Click a row for the full record.
        </p>
      </div>
      <div className="audit-grid border-t border-line">
        <GridToolbar
          search={search}
          onSearch={setSearch}
          placeholder="Search events…"
          chips={chips}
          active={actor}
          onChip={(id) => { actorRef.current = id; setActor(id); grid.current?.api.onFilterChanged(); }}
        >
          <button
            className="btn-ghost !px-3 !py-1.5 !text-xs"
            disabled={!events.length}
            onClick={() => grid.current?.api.exportDataAsCsv({
              fileName: "proofpay-audit.csv",
              processCellCallback: (p) => p.formatValue(p.value), // export the words shown, not raw codes
            })}
          >
            Export CSV
          </button>
        </GridToolbar>
        <AgGridReact<AuditEvent>
          ref={grid}
          theme={gridTheme}
          rowData={events}
          columnDefs={columns}
          getRowId={(p) => String(p.data.id)}
          defaultColDef={{ sortable: true, resizable: true }}
          domLayout="autoHeight"
          quickFilterText={search}
          isExternalFilterPresent={() => actorRef.current !== "ALL"}
          doesExternalFilterPass={(n) => n.data?.actor === actorRef.current}
          rowSelection={{ mode: "singleRow", checkboxes: false, enableClickSelection: true }}
          onRowClicked={(e) => {
            if (!e.data) return;
            if (openId === e.data.id) { e.node.setSelected(false); setOpenId(null); } // second click closes
            else setOpenId(e.data.id);
          }}
          suppressCellFocus
          suppressMultiSort
          rowClassRules={{
            "row-bad": (p) => !!p.data && BAD.has(p.data.type),
            "row-ok": (p) => !!p.data && GOOD.has(p.data.type),
          }}
          rowClass="cursor-pointer"
          pagination={events.length > 20}
          paginationPageSize={20}
          paginationPageSizeSelector={[10, 20, 50]}
          animateRows
          overlayNoRowsTemplate="No events yet."
        />
      </div>
      {open && (
        <div ref={panel} className="border-t-2 border-brand bg-slate-50 p-4">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className={`rounded px-2 py-0.5 text-xs font-semibold ${ACTOR[open.actor] ?? ACTOR.SYSTEM}`}>{label(open.actor)}</span>
            <span className="font-semibold">{label(open.type)}</span>
            <span className="font-mono text-xs text-muted">{when(open.createdAt)}</span>
            <button className="ml-auto text-xs text-muted hover:text-ink" onClick={close}>Close ✕</button>
          </div>
          <pre className="mt-2 max-h-72 overflow-auto rounded-lg border border-line bg-white p-3 text-xs">
            {pretty(open.detail) || "No further detail recorded for this event."}
          </pre>
        </div>
      )}
    </section>
  );
}
