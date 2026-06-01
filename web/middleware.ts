import { auth } from "@/auth";
import { NextResponse } from "next/server";

// Gate every route except Auth.js routes, the sign-in page itself, and Next
// internals. Unauthenticated traffic — including a session whose Google token
// refresh has failed (`RefreshAccessTokenError`) — redirects to /auth/signin so
// the user re-consents and gets a fresh refresh token, instead of every
// backend-touching page throwing "session refresh failed".
export default auth((req) => {
  if (req.auth && req.auth.error !== "RefreshAccessTokenError") {
    return NextResponse.next();
  }
  const url = new URL("/auth/signin", req.nextUrl);
  url.searchParams.set("callbackUrl", req.nextUrl.pathname + req.nextUrl.search);
  return NextResponse.redirect(url);
});

export const config = {
  matcher: [
    "/((?!api/auth|auth/signin|_next/static|_next/image|favicon.ico).*)",
  ],
};
