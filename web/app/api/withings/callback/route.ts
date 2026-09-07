import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";
import { send } from "@/lib/api";
import { WITHINGS_CALLBACK_PATH, WITHINGS_STATE_COOKIE } from "@/lib/withings";

// Withings redirects the browser back here with ?code&state after the user
// authorizes. We validate the CSRF state, then hand the code (plus the exact
// redirect_uri used) to the backend, which owns the client secret and performs
// the token exchange. On success/failure we bounce to the profile page with a
// `withings` status param the page surfaces as a banner.
export async function GET(request: NextRequest) {
  const url = request.nextUrl;
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const oauthError = url.searchParams.get("error");

  const jar = await cookies();
  const expectedState = jar.get(WITHINGS_STATE_COOKIE)?.value;
  jar.delete(WITHINGS_STATE_COOKIE);

  const profile = new URL("/me/profile", url.origin);

  if (oauthError || !code) {
    profile.searchParams.set("withings", "error");
    return NextResponse.redirect(profile);
  }
  if (!state || !expectedState || state !== expectedState) {
    // CSRF mismatch (or a stale/replayed callback) — refuse the exchange.
    profile.searchParams.set("withings", "error");
    return NextResponse.redirect(profile);
  }

  const redirectUri = `${url.origin}${WITHINGS_CALLBACK_PATH}`;
  try {
    await send("/api/me/withings/connect", "POST", { code, redirectUri });
    profile.searchParams.set("withings", "connected");
  } catch {
    profile.searchParams.set("withings", "error");
  }
  return NextResponse.redirect(profile);
}
