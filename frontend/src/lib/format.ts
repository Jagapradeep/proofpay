export function money(value: number | null | undefined, currency = "USD"): string {
  if (value === null || value === undefined) return "—";
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(value);
}

export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-US", {
    month: "short", day: "numeric", hour: "numeric", minute: "2-digit", second: "2-digit",
  });
}

export function shortSha(sha: string | null | undefined): string {
  return sha ? sha.slice(0, 7) : "—";
}

/** Turns SCREAMING_CASE status names into "Screaming case". */
export function label(s: string): string {
  const t = s.toLowerCase().replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}
