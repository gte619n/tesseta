import { proxySseStream } from "@/lib/api";
import { NextResponse } from "next/server";

// Proxies a Goals chat message to the backend SSE endpoint
// (`POST /api/me/goals/chat`) so the browser can stream the response.
//
// The backend emits named SSE events: `token` (assistant text delta),
// `proposal` (a validated GoalProposalDto), `error`, and a terminal
// `done` carrying the threadId. We forward the raw stream untouched; the
// browser-side client parses the named events.
export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as
    | { threadId?: string; message?: string }
    | null;

  if (!body || typeof body.message !== "string" || !body.message.trim()) {
    return new NextResponse("Missing message", { status: 400 });
  }

  return proxySseStream("/api/me/goals/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      threadId: body.threadId ?? null,
      message: body.message,
    }),
  });
}
