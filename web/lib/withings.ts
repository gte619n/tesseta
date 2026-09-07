// Withings OAuth2 authorization-code flow helpers (browser redirect).
//
// Unlike Google Health, Withings can't ride Auth.js: its token endpoint wraps
// responses in a non-standard { status, body } envelope and rotates the refresh
// token, so the exchange happens on the Spring backend. The web app only drives
// the browser leg — send the user to Withings' authorize page, then hand the
// returned `code` to the backend's /api/me/withings/connect.

export const WITHINGS_AUTHORIZE_URL =
  "https://account.withings.com/oauth2_user/authorize2";

// Sleep Analyzer data needs user.activity; weight/body needs user.metrics.
export const WITHINGS_SCOPE = "user.metrics,user.activity";

// The redirect the browser comes back to; must be registered on the Withings
// partner app and is echoed to the backend for the token exchange (Withings
// requires the exchange redirect_uri to match the authorize redirect_uri).
export const WITHINGS_CALLBACK_PATH = "/api/withings/callback";

// httpOnly cookie carrying the CSRF `state` between start and callback.
export const WITHINGS_STATE_COOKIE = "withings_oauth_state";

// Resolve the app's public origin for building the redirect_uri. Behind Cloud
// Run's proxy, Next's request.nextUrl.origin reflects the container's internal
// bind host (https://0.0.0.0:8080), which must never be sent to Withings as the
// redirect_uri. Prefer the canonical AUTH_URL (set in prod cloudbuild and by
// dev.sh), then the forwarded host/proto headers, then the request origin as a
// last resort (plain `next dev` on localhost, where it's already correct).
export function resolveWebOrigin(request: {
  headers: Headers;
  nextUrl: { origin: string };
}): string {
  const envUrl = process.env.AUTH_URL ?? process.env.NEXTAUTH_URL;
  if (envUrl && envUrl.trim()) return envUrl.trim().replace(/\/+$/, "");
  const host =
    request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  const proto = request.headers.get("x-forwarded-proto") ?? "https";
  return host ? `${proto}://${host}` : request.nextUrl.origin;
}

export function buildWithingsAuthorizeUrl(params: {
  clientId: string;
  redirectUri: string;
  state: string;
}): string {
  const url = new URL(WITHINGS_AUTHORIZE_URL);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", params.clientId);
  url.searchParams.set("scope", WITHINGS_SCOPE);
  url.searchParams.set("redirect_uri", params.redirectUri);
  url.searchParams.set("state", params.state);
  return url.toString();
}
