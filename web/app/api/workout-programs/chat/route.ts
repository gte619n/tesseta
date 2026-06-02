import { apiFetch } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a Workout Program designer chat message to the backend SSE endpoint
// (`POST /api/me/workout-programs/chat`) so the browser can stream the
// response. The browser can't call the backend directly — the bearer header
// lives in the Auth.js session, which is server-side only.
//
// The backend emits named SSE events: `token` (assistant text delta),
// `proposal` ({ program, issues }), `error`, and a terminal `done` carrying the
// threadId. We forward the raw stream untouched; the browser-side client parses
// the named events.
//
// First turn of a new thread: { message, schedule, goalId? } (no threadId) —
// the backend stores the schedule/goal on the thread and they are fixed for the
// conversation. Subsequent turns: { threadId, message } only.
type ChatSchedule = {
  trainingDays?: string[];
  dayLocations?: Record<string, string>;
};

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as
    | {
        threadId?: string | null;
        message?: string;
        schedule?: ChatSchedule | null;
        goalId?: string | null;
      }
    | null;

  if (!body || typeof body.message !== "string" || !body.message.trim()) {
    return new NextResponse("Missing message", { status: 400 });
  }

  const res = await apiFetch("/api/me/workout-programs/chat", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify({
      threadId: body.threadId ?? null,
      message: body.message,
      schedule: body.schedule ?? null,
      goalId: body.goalId ?? null,
    }),
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
