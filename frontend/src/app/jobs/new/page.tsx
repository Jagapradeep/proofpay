"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useApp } from "@/components/Providers";
import { api } from "@/lib/api";
import { money } from "@/lib/format";

const DEMO = {
  title: "Temperature converter CLI",
  description:
    "Build a Python command-line temperature converter that converts between Celsius, Fahrenheit and Kelvin, " +
    "rejects temperatures below absolute zero with a clear error, and includes pytest unit tests and a README " +
    "with usage examples.",
  milestones: [
    { title: "Conversion library and CLI", amount: "40" },
    { title: "Tests and documentation", amount: "60" },
  ],
};

export default function NewJobPage() {
  const router = useRouter();
  const { config } = useApp();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [clientEmail, setClientEmail] = useState(config.demoClientEmail);
  const [freelancerEmail, setFreelancerEmail] = useState(config.demoFreelancerEmail);
  const [milestones, setMilestones] = useState([{ title: "", amount: "" }, { title: "", amount: "" }]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const total = milestones.reduce((s, m) => s + (Number(m.amount) || 0), 0);

  const fillDemo = () => {
    setTitle(DEMO.title);
    setDescription(DEMO.description);
    setMilestones(DEMO.milestones.map((m) => ({ ...m })));
  };

  const update = (i: number, field: "title" | "amount", value: string) =>
    setMilestones((ms) => ms.map((m, j) => (j === i ? { ...m, [field]: value } : m)));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const job = await api.createJob({
        title, description, clientEmail, freelancerEmail, currency: "USD",
        milestones: milestones.map((m) => ({ title: m.title, amount: Number(m.amount) })),
      });
      router.push(`/jobs/${job.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Post a job</h1>
          <p className="mt-1 text-sm text-muted">Describe the work in plain English. The AI writes the acceptance criteria next.</p>
        </div>
        <button type="button" onClick={fillDemo} className="btn-ghost">Use the demo job</button>
      </div>

      <form onSubmit={submit} className="card space-y-5 p-6">
        <label className="block">
          <span className="mb-1 block text-sm font-medium">Title</span>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium">What should be built?</span>
          <textarea className="input min-h-32" value={description} onChange={(e) => setDescription(e.target.value)} required />
        </label>
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block">
            <span className="mb-1 block text-sm font-medium">Client PayPal email (pays)</span>
            <input className="input" type="email" value={clientEmail} onChange={(e) => setClientEmail(e.target.value)} required />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium">Freelancer PayPal email (gets paid)</span>
            <input className="input" type="email" value={freelancerEmail} onChange={(e) => setFreelancerEmail(e.target.value)} required />
          </label>
        </div>

        <fieldset className="space-y-3">
          <legend className="mb-1 text-sm font-medium">Milestones</legend>
          {milestones.map((m, i) => (
            <div key={i} className="flex gap-3">
              <span className="mt-2 w-6 text-sm text-muted">{i + 1}.</span>
              <input className="input" placeholder="Milestone title" value={m.title}
                onChange={(e) => update(i, "title", e.target.value)} required maxLength={200} />
              <div className="relative w-36 shrink-0">
                <span className="pointer-events-none absolute left-3 top-2 text-sm text-muted">$</span>
                <input className="input pl-7" type="number" min="1" step="0.01" placeholder="0.00" value={m.amount}
                  onChange={(e) => update(i, "amount", e.target.value)} required />
              </div>
              {milestones.length > 1 && (
                <button type="button" aria-label="Remove milestone" className="px-2 text-muted hover:text-bad"
                  onClick={() => setMilestones((ms) => ms.filter((_, j) => j !== i))}>×</button>
              )}
            </div>
          ))}
          {milestones.length < 5 && (
            <button type="button" className="text-sm font-medium text-brand"
              onClick={() => setMilestones((ms) => [...ms, { title: "", amount: "" }])}>+ Add milestone</button>
          )}
        </fieldset>

        {error && <p className="rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">{error}</p>}

        <div className="flex items-center justify-between border-t border-line pt-5">
          <span className="text-sm text-muted">Total held at funding: <b className="text-ink">{money(total)}</b></span>
          <button className="btn-primary" disabled={busy}>
            {busy && <span className="spinner" />} Create job
          </button>
        </div>
      </form>
    </div>
  );
}
