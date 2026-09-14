import { describe, it, expect, vi, beforeEach } from "vitest";

// SEC-012 regression guard: the backend photo endpoint REQUIRES `?date=` to
// scope the per-user lookup (ADR-0021, no cross-collection scan). The backend
// bakes that date into the `photoUrl` it returns, so it arrives here as the
// incoming request's query — this proxy must forward it. A previous version
// dropped the query and every web meal photo 400'd to a placeholder.
vi.mock("@/lib/api", () => ({ apiFetch: vi.fn() }));

import { apiFetch } from "@/lib/api";
import { GET } from "./route";

const mockApiFetch = vi.mocked(apiFetch);

describe("meal-photo proxy route", () => {
  beforeEach(() => mockApiFetch.mockReset());

  it("forwards the ?date query to the backend and 302s to the signed URL", async () => {
    mockApiFetch.mockResolvedValue(
      new Response(null, {
        status: 302,
        headers: { location: "https://storage.googleapis.com/bucket/signed?sig=abc" },
      }),
    );

    const req = new Request(
      "https://app.tesseta.com/api/me/nutrition/photo/e1?date=2026-09-14",
    );
    const res = await GET(req, { params: Promise.resolve({ entryId: "e1" }) });

    expect(mockApiFetch).toHaveBeenCalledTimes(1);
    // Assert on the call args without array indexing (repo tsconfig has
    // noUncheckedIndexedAccess): the forwarded path must carry the entry + date.
    expect(mockApiFetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/me/nutrition/photo/e1"),
      expect.anything(),
    );
    expect(mockApiFetch).toHaveBeenCalledWith(
      expect.stringContaining("date=2026-09-14"), // the forwarding, the whole point
      expect.anything(),
    );
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(
      "https://storage.googleapis.com/bucket/signed?sig=abc",
    );
  });

  it("passes a non-redirect status through so the <img> falls back", async () => {
    mockApiFetch.mockResolvedValue(new Response(null, { status: 404 }));

    const req = new Request(
      "https://app.tesseta.com/api/me/nutrition/photo/e1?date=2026-09-14",
    );
    const res = await GET(req, { params: Promise.resolve({ entryId: "e1" }) });

    expect(res.status).toBe(404);
  });
});
