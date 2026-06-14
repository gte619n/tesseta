import { apiFetch, BackendError } from "@/lib/api";
import { NextResponse } from "next/server";

import type { TrtContext } from "@/lib/types/trt";

// Proxies the TRT / monitoring-panel context for the program-designer chat
// (IMPL-18 / ADR-0015) to the backend
// `GET /api/me/workout-programs/chat/trt-context`. Mirrors the chat route's
// auth + BACKEND_URL proxy: the bearer header lives in the server-side Auth.js
// session, so the browser can't call the backend directly.
export async function GET() {
  try {
    const res = await apiFetch("/api/me/workout-programs/chat/trt-context", {
      method: "GET",
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      return new NextResponse(text || `Backend returned ${res.status}`, {
        status: res.status,
      });
    }
    const data = (await res.json()) as TrtContext;
    return NextResponse.json(data);
  } catch (e) {
    const status = e instanceof BackendError ? e.status : 500;
    return new NextResponse("Failed to load TRT context", { status });
  }
}
