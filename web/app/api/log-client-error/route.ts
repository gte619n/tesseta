import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

// OBS-002 — web client crash reporting with no external DSN. The App Router
// error boundaries (app/error.tsx, app/global-error.tsx) POST the caught error
// here; we emit a single structured JSON line to stderr. On Cloud Run stderr is
// captured by Cloud Logging, so a client crash becomes a queryable log entry the
// operator can alert on — without Sentry or any third-party sub-processor.
//
// PII discipline: we log only the error shape (message, trimmed stack, digest)
// and the pathname. We never log form values, query strings, cookies, or the
// user identity — a crash report is diagnostic, not an audit of user input.

// Cap the stack so a runaway trace can't blow up a log line.
const MAX_STACK_CHARS = 4000;
const MAX_MESSAGE_CHARS = 1000;

function clip(value: unknown, max: number): string | undefined {
  if (typeof value !== "string") return undefined;
  return value.length > max ? `${value.slice(0, max)}…[truncated]` : value;
}

export async function POST(request: Request) {
  let body: {
    message?: unknown;
    stack?: unknown;
    digest?: unknown;
    pathname?: unknown;
  } = {};
  try {
    body = await request.json();
  } catch {
    // Malformed payload — still record that a client error was reported.
  }

  // Strip anything after "?" / "#" so query strings (which may carry PII) never
  // reach the log; keep only the route path.
  const rawPath = typeof body.pathname === "string" ? body.pathname : undefined;
  const pathname = rawPath?.split(/[?#]/)[0];

  const entry = {
    severity: "ERROR",
    kind: "web-client-error",
    message: clip(body.message, MAX_MESSAGE_CHARS) ?? "(no message)",
    stack: clip(body.stack, MAX_STACK_CHARS),
    digest: typeof body.digest === "string" ? body.digest : undefined,
    pathname,
    userAgent: request.headers.get("user-agent") ?? undefined,
    ts: new Date().toISOString(),
  };

  // Structured single-line JSON → Cloud Logging picks up `severity`/`message`.
  console.error(JSON.stringify(entry));

  // 204: fire-and-forget from the boundary; nothing to return.
  return new NextResponse(null, { status: 204 });
}
