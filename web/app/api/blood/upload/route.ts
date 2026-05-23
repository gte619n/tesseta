import { apiFetch } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a blood test upload to the backend so the browser can stream
// the SSE response without holding the user's Google ID token. The
// browser can't call the backend directly — the bearer header lives in
// the Auth.js session, which is server-side only.
//
// We re-build the FormData (rather than piping the raw request body)
// because the backend's multipart parser expects the same boundary
// header that fetch() generates for us.
export async function POST(request: Request) {
  const incoming = await request.formData();
  const file = incoming.get("file");
  if (!(file instanceof File)) {
    return new NextResponse("Missing file", { status: 400 });
  }

  const forwarded = new FormData();
  forwarded.set("file", file);

  const res = await apiFetch("/api/me/blood/reports", {
    method: "POST",
    body: forwarded,
    // Tell apiFetch's fetch() not to buffer the response — we need to
    // pass the SSE stream straight through.
    headers: { Accept: "text/event-stream" },
  });

  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => "");
    return new NextResponse(text || `Backend returned ${res.status}`, {
      status: res.status,
    });
  }

  return new NextResponse(res.body, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      // Some proxies buffer event streams; this header disables that on
      // common reverse proxies (nginx, Cloud Run's load balancer).
      "X-Accel-Buffering": "no",
    },
  });
}
