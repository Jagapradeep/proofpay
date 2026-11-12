"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { api } from "@/lib/api";

/** PayPal sends the buyer here after approving on paypal.com (redirect fallback). We place the hold. */
function FundReturn() {
  const router = useRouter();
  const params = useSearchParams();
  const token = params.get("token"); // PayPal order id
  const [message, setMessage] = useState("Placing the hold on PayPal…");
  const [jobId, setJobId] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!token) {
      setMessage("Missing PayPal order token.");
      setFailed(true);
      return;
    }
    (async () => {
      try {
        const job = (await api.jobs()).find((j) => j.funding?.paypalOrderId === token);
        if (!job) throw new Error("No job found for this PayPal order.");
        setJobId(job.id);
        // Reopening an old return link must not try to place a second hold.
        if (!["FUNDED", "COMPLETED", "CANCELLED"].includes(job.status)) await api.authorize(job.id);
        router.replace(`/jobs/${job.id}`);
      } catch (e) {
        setMessage((e as Error).message);
        setFailed(true);
      }
    })();
  }, [token, router]);

  return (
    <div className="card mx-auto max-w-lg p-8 text-center">
      {!failed && <span className="spinner mx-auto mb-4 block text-brand" />}
      <p className={failed ? "text-bad" : "text-muted"}>{message}</p>
      {jobId && failed && <Link className="btn-ghost mt-4" href={`/jobs/${jobId}`}>Back to the job</Link>}
    </div>
  );
}

export default function FundReturnPage() {
  return (
    <Suspense fallback={null}>
      <FundReturn />
    </Suspense>
  );
}
