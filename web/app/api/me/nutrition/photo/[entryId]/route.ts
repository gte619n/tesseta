import { NextResponse } from "next/server";
import { apiFetch } from "@/lib/api";

export const dynamic = "force-dynamic";

// SEC-012 — proxy meal-photo serving. The backend endpoint
// GET /api/me/nutrition/photo/{entryId} authorizes the entry belongs to the
// caller (bearer required) then 302-redirects to a short-lived V4 signed GCS
// URL. The browser can't call the backend directly (the bearer lives only in
// the server-side Auth.js session), so <img>/next-image point at this same-
// origin route, which attaches auth via apiFetch and forwards the redirect.
//
// We capture the backend 302 with redirect:"manual" and re-emit it to the
// browser, which then loads the signed storage.googleapis.com URL directly
// (already allowlisted in next.config.ts remotePatterns).
export async function GET(
  request: Request,
  ctx: { params: Promise<{ entryId: string }> },
) {
  const { entryId } = await ctx.params;
  if (!entryId) {
    return new NextResponse("Missing entryId", { status: 400 });
  }

  // Forward the query string — the backend endpoint requires `?date=` to scope
  // the per-user entry lookup (ADR-0021: no cross-collection scan). The date is
  // already baked into the backend-provided `photoUrl`, so it arrives here as the
  // incoming request's query; drop it and the backend 400s and photos vanish.
  const search = new URL(request.url).search;
  const res = await apiFetch(
    `/api/me/nutrition/photo/${encodeURIComponent(entryId)}${search}`,
    { redirect: "manual" },
  );

  // Backend signals the signed URL via a 3xx Location. Re-issue it as a browser
  // redirect (302) so the <img> follows it to GCS. Don't cache — the signed URL
  // is short-lived (TTL 15m), so the browser must re-resolve after expiry.
  const location = res.headers.get("location");
  if (location) {
    return NextResponse.redirect(location, {
      status: 302,
      headers: { "Cache-Control": "no-store" },
    });
  }

  // No redirect (404 / not-authorized / no photo) — pass the status through so
  // the <img> falls back to the placeholder rather than showing a broken image.
  return new NextResponse(null, { status: res.status || 404 });
}
