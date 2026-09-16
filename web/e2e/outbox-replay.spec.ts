import { expect, test } from "@playwright/test";

// Slice 4b: the outbox drains through POST /api/outbox/replay, which attaches
// the user's bearer token and forwards to the backend. It must ONLY forward
// paths on the replay allowlist — otherwise a page could proxy any backend
// endpoint under the user's identity. These run against the real dev server (no
// auth needed: the allowlist gate is checked before any backend/auth call).
//
// The full authenticated offline→online→sync-once UI flow needs a running
// backend + session (see docs/refactor/perf-harness.md for the manual stack);
// the outbox mechanics themselves are unit-tested in lib/offline/*.test.ts.

test("replay proxy rejects a non-allowlisted path with 400", async ({ request }) => {
  const res = await request.post("/api/outbox/replay", {
    data: { id: "m1", method: "POST", path: "/api/admin/drugs", body: {} },
  });
  expect(res.status()).toBe(400);
  expect((await res.json()).error).toContain("not replayable");
});

test("replay proxy rejects a malformed body with 400", async ({ request }) => {
  const res = await request.post("/api/outbox/replay", {
    data: { method: "POST", path: "/api/me/nutrition/2026-09-16/entries" }, // no id
  });
  expect(res.status()).toBe(400);
});

test("replay proxy lets an allowlisted path through the gate (fails later at auth, not 400)", async ({
  request,
}) => {
  // An allowlisted path passes the allowlist check and reaches apiFetch, which
  // fails without a session — so the status is NOT the 400 the allowlist emits.
  // This proves the gate distinguishes allowed paths from rejected ones.
  const res = await request.post("/api/outbox/replay", {
    data: {
      id: "m2",
      method: "POST",
      path: "/api/me/nutrition/2026-09-16/entries",
      body: { id: "m2", meal: "LUNCH" },
    },
  });
  expect(res.status()).not.toBe(400);
});
