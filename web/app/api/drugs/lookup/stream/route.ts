import { proxySseStream } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a drug lookup to the backend so the browser can stream the SSE
// response. (proxySseStream explains why the browser can't call the backend
// directly.)
export async function POST(request: Request) {
  const body = await request.json();

  if (!body.query || typeof body.query !== "string") {
    return new NextResponse("Missing query", { status: 400 });
  }

  return proxySseStream("/api/drugs/lookup/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: body.query }),
  });
}
