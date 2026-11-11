"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LogoMark } from "./Logo";
import { useApp, type Role } from "./Providers";

const NAV = [
  { href: "/", label: "Jobs" },
  { href: "/ledger", label: "Ledger" },
];

export function Header() {
  const { role, setRole } = useApp();
  const path = usePathname();

  return (
    <header className="sticky top-0 z-20 border-b border-line bg-white/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3">
        <Link href="/" className="flex items-center gap-2">
          <LogoMark className="h-8 w-8" />
          <span className="text-lg font-semibold tracking-tight">ProofPay</span>
          <span className="hidden rounded-full bg-warn-soft px-2 py-0.5 text-xs font-medium text-warn sm:inline">
            PayPal sandbox
          </span>
        </Link>
        <nav className="flex gap-1">
          {NAV.map((n) => {
            const active = n.href === "/" ? path === "/" || path.startsWith("/jobs") : path.startsWith(n.href);
            return (
              <Link
                key={n.href}
                href={n.href}
                className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                  active ? "bg-brand-soft text-brand" : "text-muted hover:text-ink"
                }`}
              >
                {n.label}
              </Link>
            );
          })}
        </nav>
        <div className="ml-auto flex items-center gap-2 text-sm">
          <span className="text-muted">Viewing as</span>
          <div className="flex rounded-lg border border-line p-0.5" role="radiogroup" aria-label="Role">
            {(["CLIENT", "FREELANCER"] as Role[]).map((r) => (
              <button
                key={r}
                role="radio"
                aria-checked={role === r}
                onClick={() => setRole(r)}
                className={`rounded-md px-3 py-1 font-medium ${
                  role === r ? "bg-ink text-white" : "text-muted hover:text-ink"
                }`}
              >
                {r === "CLIENT" ? "Client" : "Freelancer"}
              </button>
            ))}
          </div>
        </div>
      </div>
    </header>
  );
}
