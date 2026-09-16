import { NextRequest, NextResponse } from "next/server";
import { apiFetch, BackendError, ForbiddenError, UnauthenticatedError } from "@/lib/api";
import { isReplayable } from "@/lib/offline/replay-endpoints";

// The single authenticated choke point the web mutation outbox drains through.
// The client can't call the backend directly (no bearer token in the browser),
// so it POSTs a queued mutation here; this handler attaches the session bearer
// and the Idempotency-Key and forwards it to the backend.
//
// SECURITY: it forwards under the user's identity, so it must never proxy an
// arbitrary path. Only (method, path) pairs on the REPLAY_ENDPOINTS allowlist —
// the user-data writes proven replay-safe by the backend write contract — are
// forwarded; anything else is a 400 before any backend call.

interface ReplayBody {
  id?: string;
  method?: string;
  path?: string;
  body?: unknown;
}

export async function POST(req: NextRequest): Promise<NextResponse> {
  let payload: ReplayBody;
  try {
    payload = (await req.json()) as ReplayBody;
  } catch {
    return NextResponse.json({ error: "invalid JSON" }, { status: 400 });
  }

  const { id, method, path, body } = payload;
  if (!id || !method || !path) {
    return NextResponse.json({ error: "id, method and path are required" }, { status: 400 });
  }
  const upper = method.toUpperCase();
  if (!isReplayable(upper, path)) {
    // Not on the allowlist — refuse rather than forward under the user's token.
    return NextResponse.json({ error: "endpoint not replayable" }, { status: 400 });
  }

  try {
    const res = await apiFetch(path, {
      method: upper,
      headers: {
        "Content-Type": "application/json",
        // The client UUID doubles as the server-side idempotency guard, so a
        // replayed mutation returns the original result instead of duplicating.
        "Idempotency-Key": id,
      },
      ...(body !== undefined && body !== null ? { body: JSON.stringify(body) } : {}),
    });
    const text = await res.text();
    // Mirror the backend status so the outbox drain can distinguish terminal
    // (4xx) rejections from transient (5xx / network) failures.
    return new NextResponse(text || null, {
      status: res.status,
      headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" },
    });
  } catch (e) {
    if (e instanceof UnauthenticatedError) {
      // Session expired while offline: keep the row queued (retryable) rather
      // than parking it — the drain re-attempts once the session refreshes.
      return NextResponse.json({ error: "unauthenticated" }, { status: 503 });
    }
    if (e instanceof ForbiddenError) {
      return NextResponse.json({ error: "forbidden" }, { status: 403 });
    }
    if (e instanceof BackendError) {
      return NextResponse.json({ error: e.message }, { status: e.status });
    }
    // Network / unexpected: transient, let the drain back off and retry.
    return NextResponse.json({ error: (e as Error).message }, { status: 503 });
  }
}
