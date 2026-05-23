import { apiFetch } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies the blood test PDF download from the backend.
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ reportId: string }> }
) {
  const { reportId } = await params;

  const res = await apiFetch(`/api/me/blood/reports/${reportId}/pdf`);

  if (!res.ok) {
    return new NextResponse(null, { status: res.status });
  }

  const pdf = await res.arrayBuffer();

  return new NextResponse(pdf, {
    status: 200,
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `inline; filename="bloodtest-${reportId}.pdf"`,
    },
  });
}
