// Server-side proxy: browser -> Next.js -> Spring Boot. Adds the API key so it never reaches the browser.
import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const BACKEND = (process.env.BACKEND_URL ?? "http://localhost:8080").replace(/\/$/, "");
const SAFE_SEGMENT = /^[A-Za-z0-9_-]+$/;

async function forward(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  // Webhooks go straight from PayPal to the backend, never through here.
  if (path[0] === "webhooks" || !path.every((p) => SAFE_SEGMENT.test(p))) {
    return Response.json({ status: 404, error: "Not Found", message: "Unknown API path" }, { status: 404 });
  }
  const headers: Record<string, string> = {
    "X-Forwarded-For": req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown",
  };
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
