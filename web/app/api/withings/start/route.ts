import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";
import {
  buildWithingsAuthorizeUrl,
  resolveWebOrigin,
  WITHINGS_CALLBACK_PATH,
  WITHINGS_STATE_COOKIE,
} from "@/lib/withings";

// Kicks off the Withings connect flow: mint a CSRF `state`, stash it in an
// httpOnly cookie, and redirect the browser to Withings' authorize page. The
// "Connect Withings" button on the profile page is just a link here.
export async function GET(request: NextRequest) {
  const clientId = process.env.WITHINGS_CLIENT_ID;
  if (!clientId) {
    return new NextResponse("Withings integration is not configured", { status: 500 });
  }
  const origin = resolveWebOrigin(request);
  const redirectUri = `${origin}${WITHINGS_CALLBACK_PATH}`;
  const state = crypto.randomUUID();

  (await cookies()).set(WITHINGS_STATE_COOKIE, state, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax", // survives the top-level redirect back from Withings
    path: "/",
    maxAge: 600,
  });

  return NextResponse.redirect(
    buildWithingsAuthorizeUrl({ clientId, redirectUri, state }),
  );
}
