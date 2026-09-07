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
