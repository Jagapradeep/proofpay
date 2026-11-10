"use client";

import { PayPalButtons, PayPalScriptProvider } from "@paypal/react-paypal-js";
import { useState } from "react";
import { api } from "@/lib/api";
import { money, when } from "@/lib/format";
import type { Job } from "@/lib/types";
import { useApp } from "./Providers";
import { StatusBadge } from "./StatusBadge";

export function FundingPanel({ job, onChange }: { job: Job; onChange: () => void }) {
  const { role, config } = useApp();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const accepted = !!job.clientAcceptedCriteriaAt && !!job.freelancerAcceptedCriteriaAt;
  const canFund = accepted && (job.status === "DRAFT" || job.status === "AWAITING_FUNDING");
  const f = job.funding;
  const funded = f && f.paypalAuthorizationId;

  // Fallback when the PayPal button can't load: full-page redirect; /fund/return authorizes.
  const redirectToPayPal = async () => {
    setBusy(true);
    setError(null);
    try {
      const order = await api.createOrder(job.id);
      window.location.href = order.approveUrl;
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  const voidHold = async () => {
    if (!window.confirm("Cancel the job and release the PayPal hold? Nothing will be charged.")) return;
    setBusy(true);
    setError(null);
    try {
      await api.voidFunding(job.id);
      onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card p-5">
      <h2 className="section-title">Step 2 · Fund with PayPal</h2>
      <p className="mt-1 text-sm text-muted">
        The client authorizes the full amount. PayPal <b className="text-ink">holds</b> it; nothing is charged until a
        milestone passes the policy gate.
      </p>

      {funded && f ? (
        <div className="mt-4 grid gap-3 sm:grid-cols-4">
          <Stat label="Held" value={money(f.amount, job.currency)} />
          <Stat label="Captured" value={money(f.capturedAmount, job.currency)} tone="ok" />
          <Stat label="Remaining" value={money(f.remaining, job.currency)} />
          <div className="rounded-lg border border-line p-3">
            <div className="text-xs text-muted">Authorization</div>
            <div className="mt-1 flex items-center gap-2"><StatusBadge status={f.status} /></div>
            <div className="mt-1 truncate font-mono text-xs text-muted" title={f.paypalAuthorizationId ?? ""}>
              {f.paypalAuthorizationId}
            </div>
            {f.expiresAt && <div className="text-xs text-muted">expires {when(f.expiresAt)}</div>}
          </div>
        </div>
      ) : !accepted ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-4 text-sm text-muted">Both sides must accept the criteria first.</p>
      ) : role !== "CLIENT" ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-4 text-sm text-muted">Waiting for the client to fund the job.</p>
      ) : canFund ? (
        <div className="mt-4 max-w-sm">
          {config.paypalClientId ? (
            <PayPalScriptProvider options={{ clientId: config.paypalClientId, intent: "authorize", currency: job.currency }}>
              <PayPalButtons
                style={{ layout: "vertical", label: "pay", height: 45 }}
                disabled={busy}
                createOrder={async () => (await api.createOrder(job.id)).orderId}
                onApprove={async () => {
                  setBusy(true);
                  try {
                    await api.authorize(job.id);
                    onChange();
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy(false);
                  }
                }}
                onError={(e) => setError(`PayPal: ${String(e)}`)}
              />
            </PayPalScriptProvider>
          ) : (
            <p className="mb-3 text-xs text-warn">PAYPAL_CLIENT_ID is not set for the frontend; using the redirect flow.</p>
          )}
          <button className="mt-2 text-xs font-medium text-brand underline-offset-2 hover:underline" disabled={busy}
            onClick={redirectToPayPal}>
            Button not loading? Continue on paypal.com instead
          </button>
          <p className="mt-3 text-xs text-muted">Sandbox: log in with the sandbox <b>buyer</b> account. Total {money(job.totalAmount, job.currency)}.</p>
        </div>
      ) : null}

      {busy && <p className="mt-3 flex items-center gap-2 text-sm text-muted"><span className="spinner" /> Talking to PayPal…</p>}
      {role === "CLIENT" && job.status === "FUNDED" && f && f.capturedAmount === 0 && (
        <button className="btn-danger mt-4" disabled={busy} onClick={voidHold}>Cancel job and release the hold</button>
      )}
      {error && <p className="mt-3 rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">{error}</p>}
    </section>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: "ok" }) {
  return (
    <div className="rounded-lg border border-line p-3">
      <div className="text-xs text-muted">{label}</div>
      <div className={`mt-1 text-xl font-semibold ${tone === "ok" ? "text-ok" : ""}`}>{value}</div>
    </div>
  );
}
