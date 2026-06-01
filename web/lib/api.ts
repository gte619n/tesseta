import { cache } from "react";
import { auth } from "@/auth";

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
  const url = `${BACKEND_URL.replace(/\/$/, "")}${path}`;
  // GET/HEAD reads stay cacheable/dedupable within a render; mutations force
  // no-store. An explicit caller-supplied `cache` always wins.
  const method = (init.method ?? "GET").toUpperCase();
  const cacheMode: RequestCache =
    init.cache ?? (READ_METHODS.has(method) ? "default" : "no-store");
  return fetch(url, {
    ...init,
    headers: {
      ...init.headers,
      Authorization: `Bearer ${session.idToken}`,
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
