import { apiFetch } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a drug lookup to the backend so the browser can stream
// the SSE response. The browser can't call the backend directly —
// the bearer header lives in the Auth.js session, which is server-side only.
export async function POST(request: Request) {
  const body = await request.json();

  if (!body.query || typeof body.query !== "string") {
    return new NextResponse("Missing query", { status: 400 });
  }

  const res = await apiFetch("/api/drugs/lookup/stream", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify({ query: body.query }),
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
      "X-Accel-Buffering": "no",
    },
  });
}
