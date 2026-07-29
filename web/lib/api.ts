import { cache } from "react";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { auth } from "@/auth";
import { isAdminEmail } from "@/lib/admin";

// Server-only fetch wrapper that pulls the current ID token from the Auth.js
// session and attaches it as a bearer header. Throws if the session is
// missing or in an error state — callers should let the error propagate to
// trigger the middleware sign-in redirect on the next request.
//
// `BACKEND_URL` is the Spring Boot service base, e.g. http://localhost:8080
// during dev or the Cloud Run URL in production.

const BACKEND_URL = process.env.BACKEND_URL;

// Resolve the Auth.js session once per server render. React's cache() dedupes
// the call across every apiFetch in a single render pass, so the JWT is
// validated once instead of once per backend call.
const getSession = cache(async () => auth());

// HTTP methods that only read data — safe to let the fetch cache dedupe them
// within a render. Anything else (POST/PUT/PATCH/DELETE) is a mutation and
// must never be cached.
const READ_METHODS = new Set(["GET", "HEAD"]);

export class UnauthenticatedError extends Error {}
export class ForbiddenError extends Error {}
export class BackendError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

export async function apiFetch(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  if (!BACKEND_URL) {
    throw new Error("BACKEND_URL is not configured");
  }
  const session = await getSession();
  if (!session || !session.idToken) {
    throw new UnauthenticatedError("no valid session");
  }
  // Only treat refresh failures as fatal — GoogleHealthConnectError just
  // means a one-time forward to the backend didn't land. The rest of the
  // session is still valid and the user should be able to retry the
  // connect flow.
  if (session.error === "RefreshAccessTokenError") {
    throw new UnauthenticatedError("session refresh failed");
  }
  // Defence-in-depth: every /api/admin/** call (read or mutation) is admin-only.
  // Server actions are independently-addressable POST endpoints, so relying on
  // the /admin layout's requireAdmin() to gate them is not enough — check here,
  // at the one choke point every admin helper funnels through. (The backend
  // enforces @AdminOnly too; this stops a non-admin action reaching it at all.)
  if (path.startsWith("/api/admin/") && !isAdminEmail(session.user?.email)) {
    throw new ForbiddenError("admin access required");
  }
  const url = `${BACKEND_URL.replace(/\/$/, "")}${path}`;
  // GET/HEAD reads stay cacheable/dedupable within a render; mutations force
  // no-store. An explicit caller-supplied `cache` always wins.
  const method = (init.method ?? "GET").toUpperCase();
  const cacheMode: RequestCache =
    init.cache ?? (READ_METHODS.has(method) ? "default" : "no-store");
  // Forward the browser's IANA zone (recorded in the `tz` cookie by
  // <TimezoneCookie/>) so the backend resolves the user's local "today"
  // rather than this server's UTC clock. Absent on the very first request
  // before the cookie is set; the backend falls back to its own zone.
  // cookies() throws outside a request scope (e.g. prerender, unit tests) —
  // treat that the same as "no tz" rather than failing the fetch.
  let tz: string | undefined;
  try {
    tz = (await cookies()).get("tz")?.value;
  } catch {
    tz = undefined;
  }
  return fetch(url, {
    ...init,
    headers: {
      ...init.headers,
      Authorization: `Bearer ${session.idToken}`,
      ...(tz ? { "X-Timezone": decodeURIComponent(tz) } : {}),
    },
    cache: cacheMode,
  });
}

export async function apiJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await apiFetch(path, init);
  if (!res.ok) {
    throw new BackendError(`${path} returned ${res.status}`, res.status);
  }
  return res.json() as Promise<T>;
}

// Server-only JSON mutation helper shared by the `lib/*-api.ts` modules. Sends
// a POST/PATCH/PUT/DELETE (with an optional JSON body), throws BackendError on a
// non-OK status, and tolerates empty (204) responses — DELETE/reorder endpoints
// return no body to parse.
export async function send<T>(
  path: string,
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  body?: unknown,
): Promise<T> {
  const res = await apiFetch(path, {
    method,
    ...(body !== undefined
      ? {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    throw new BackendError(`${method} ${path} returned ${res.status}`, res.status);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

// SSE proxy headers — `X-Accel-Buffering: no` disables event-stream buffering
// on common reverse proxies (nginx, Cloud Run's load balancer).
const SSE_HEADERS = {
  "Content-Type": "text/event-stream",
  "Cache-Control": "no-cache, no-transform",
  Connection: "keep-alive",
  "X-Accel-Buffering": "no",
} as const;

// Proxies a backend SSE endpoint straight through to the browser. The browser
// can't call the backend directly — the bearer header lives in the Auth.js
// session, which is server-side only — so route handlers forward through here.
// Forces `Accept: text/event-stream` and pipes the raw stream untouched; the
// browser-side client parses the named events. On a non-OK/empty response the
// backend's error text is returned with its status.
export async function proxySseStream(
  path: string,
  init: RequestInit = {},
): Promise<NextResponse> {
  const res = await apiFetch(path, {
    ...init,
    headers: { ...init.headers, Accept: "text/event-stream" },
  });
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => "");
    return new NextResponse(text || `Backend returned ${res.status}`, {
      status: res.status,
    });
  }
  return new NextResponse(res.body, { status: 200, headers: SSE_HEADERS });
}
