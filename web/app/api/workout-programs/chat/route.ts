import { proxySseStream } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a Workout Program designer chat message to the backend SSE endpoint
// (`POST /api/me/workout-programs/chat`) so the browser can stream the
// response.
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
        // IMPL-18b: bind a new thread to an active program for in-place editing.
        programId?: string | null;
      }
    | null;

  if (!body || typeof body.message !== "string" || !body.message.trim()) {
    return new NextResponse("Missing message", { status: 400 });
  }

  return proxySseStream("/api/me/workout-programs/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      threadId: body.threadId ?? null,
      message: body.message,
      schedule: body.schedule ?? null,
      goalId: body.goalId ?? null,
      programId: body.programId ?? null,
    }),
  });
}
