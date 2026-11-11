// Server-side proxy: browser -> Next.js -> Spring Boot. Adds the API key so it never reaches the browser.
import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const BACKEND = (process.env.BACKEND_URL ?? "http://localhost:8080").replace(/\/$/, "");
const SAFE_SEGMENT = /^[A-Za-z0-9_-]+$/;

// The caller's address for the backend's per-IP rate limit. The FIRST X-Forwarded-For entry is whatever the
// caller typed, so it is never used. Cloudflare (in front of Render) overwrites CF-Connecting-IP / True-Client-IP
// with the real address; failing those, the LAST X-Forwarded-For entry is the one our own edge appended.
function clientIp(req: NextRequest): string {
  const edge = req.headers.get("cf-connecting-ip") ?? req.headers.get("true-client-ip");
  if (edge && /^[0-9a-fA-F:.]{3,45}$/.test(edge.trim())) return edge.trim();
  const last = req.headers.get("x-forwarded-for")?.split(",").pop()?.trim();
  return last && /^[0-9a-fA-F:.]{3,45}$/.test(last) ? last : "unknown";
}

async function forward(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  // Webhooks go straight from PayPal to the backend, never through here. The manual-release test endpoint is
  // never reachable from a browser (the backend also keeps it switched off).
  if (path[0] === "webhooks" || path.includes("propose-release") || !path.every((p) => SAFE_SEGMENT.test(p))) {
    return Response.json({ status: 404, error: "Not Found", message: "Unknown API path" }, { status: 404 });
  }
  const headers: Record<string, string> = { "X-Forwarded-For": clientIp(req) };
  const key = process.env.PROOFPAY_API_KEY;
  if (key) headers["X-Api-Key"] = key;
  const contentType = req.headers.get("content-type");
  if (contentType) headers["Content-Type"] = contentType;

  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  try {
    const res = await fetch(`${BACKEND}/api/${path.join("/")}${req.nextUrl.search}`, {
      method: req.method,
      headers,
      body: hasBody ? await req.text() : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(180_000), // AI verification can take ~30 s
    });
    return new Response(res.status === 204 ? null : await res.text(), {
      status: res.status,
      headers: { "Content-Type": res.headers.get("content-type") ?? "application/json" },
    });
  } catch (e) {
    const message = e instanceof Error && e.name === "TimeoutError"
      ? "The backend took too long to answer"
      : "The backend is not reachable (it may be waking up; try again in a minute)";
    return Response.json({ status: 502, error: "Bad Gateway", message }, { status: 502 });
  }
}

export { forward as GET, forward as POST };
