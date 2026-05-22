import { apiFetch } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies the backend's authenticated PDF download. The browser can't hit
// the backend directly because it doesn't have the Google ID token; this
// route runs server-side, so apiFetch can attach the bearer header from
// the Auth.js session.
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ scanId: string }> },
) {
  const { scanId } = await params;
  const res = await apiFetch(`/api/me/dexa/scans/${scanId}/pdf`);
  if (!res.ok) {
    return new NextResponse(`Backend returned ${res.status}`, {
      status: res.status,
    });
  }
  // Stream the response body through. NextResponse handles ReadableStream
  // bodies natively.
  return new NextResponse(res.body, {
    status: 200,
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `inline; filename="dexa-${scanId}.pdf"`,
    },
  });
}
