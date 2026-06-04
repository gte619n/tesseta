import { proxySseStream } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a blood test upload to the backend so the browser can stream the SSE
// response without holding the user's Google ID token. (proxySseStream explains
// why the browser can't call the backend directly.)
//
// We re-build the FormData (rather than piping the raw request body) because
// the backend's multipart parser expects the same boundary header that fetch()
// generates for us.
export async function POST(request: Request) {
  const incoming = await request.formData();
  const file = incoming.get("file");
  if (!(file instanceof File)) {
    return new NextResponse("Missing file", { status: 400 });
  }

  const forwarded = new FormData();
  forwarded.set("file", file);

  return proxySseStream("/api/me/blood/reports", {
    method: "POST",
    body: forwarded,
  });
}
