import { apiFetch, BackendError } from "@/lib/api";
import { NextRequest, NextResponse } from "next/server";
import type { E1rmHistory } from "@/lib/types/workout-stats";

export const dynamic = "force-dynamic";

// Client-side proxy for the strength chart's lift picker
// (IMPL-WEB-WORKOUT-01 §6): the browser can't call the backend directly (the
// bearer header lives in the server-side Auth.js session), so the picker fetches
// through here. Mirrors the trt-context proxy's auth + BACKEND_URL handling.
export async function GET(request: NextRequest) {
  const exerciseId = request.nextUrl.searchParams.get("exerciseId");
  if (!exerciseId) {
    return new NextResponse("exerciseId is required", { status: 400 });
  }
  try {
    const res = await apiFetch(
      `/api/me/workout-stats/e1rm-history?exerciseId=${encodeURIComponent(exerciseId)}`,
      { method: "GET" },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      return new NextResponse(text || `Backend returned ${res.status}`, {
        status: res.status,
      });
    }
    const data = (await res.json()) as E1rmHistory;
    return NextResponse.json(data);
  } catch (e) {
    const status = e instanceof BackendError ? e.status : 500;
    return new NextResponse("Failed to load e1RM history", { status });
  }
}
