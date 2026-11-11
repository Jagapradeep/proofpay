/**
 * ProofPay mark: a shield (the money is held safely) with a check (released on proof).
 * Same drawing as src/app/icon.svg, which Next.js serves as the browser-tab icon.
 */
export function LogoMark({ className = "h-8 w-8" }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} role="img" aria-label="ProofPay logo">
      <defs>
        <linearGradient id="pp-logo-bg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#2563eb" />
          <stop offset="1" stopColor="#1e3a8a" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="8" fill="url(#pp-logo-bg)" />
      <path d="M16 5.2 25 8.6v6.7c0 5.5-3.7 9.6-9 11.5-5.3-1.9-9-6-9-11.5V8.6z" fill="#fff" />
      <path d="m11.4 16.1 3.3 3.3 6-6.6" fill="none" stroke="#1e40af" strokeWidth="2.8"
        strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
