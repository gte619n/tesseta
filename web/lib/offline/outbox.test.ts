import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { closeDb, getAll, OutboxRecord, STORE_OUTBOX } from "./idb";
import {
  drain,
  enqueue,
  jitteredBackoffMs,
  listPending,
  pendingCount,
  rearmAll,
  ReplayTransport,
} from "./outbox";
import { isReplayable } from "./replay-endpoints";

// Fresh IndexedDB per test. Close the cached connection first so deleteDatabase
// doesn't block on it.
async function wipe() {
  closeDb();
  await new Promise<void>((resolve) => {
    const req = indexedDB.deleteDatabase("tesseta-offline");
    req.onsuccess = () => resolve();
    req.onerror = () => resolve();
    req.onblocked = () => resolve();
  });
}

const T0 = 1_000; // controlled clock for enqueue + drain
const NUTRITION_POST = {
  now: T0,
  kind: "nutrition.addEntry",
  endpoint: "nutrition.entries.create",
  method: "POST" as const,
  path: "/api/me/nutrition/2026-09-16/entries",
  body: { foodId: "f1", meal: "lunch" },
};

beforeEach(wipe);
afterEach(wipe);

describe("replay allowlist", () => {
  it("accepts the known user-data writes and rejects everything else", () => {
    expect(isReplayable("POST", "/api/me/nutrition/2026-09-16/entries")).toBe(true);
    expect(isReplayable("PATCH", "/api/me/nutrition/2026-09-16/entries/e1")).toBe(true);
    expect(isReplayable("POST", "/api/me/medications/m1/adherence")).toBe(true);
    expect(isReplayable("DELETE", "/api/me/medications/m1/adherence/2026-09-17/MORNING")).toBe(true);
    // Not on the allowlist — a compromised/buggy caller must not proxy these.
    expect(isReplayable("POST", "/api/admin/drugs")).toBe(false);
    expect(isReplayable("DELETE", "/api/me/goals/g1")).toBe(false);
    expect(isReplayable("GET", "/api/me/nutrition/2026-09-16/entries")).toBe(false);
  });
});

describe("enqueue", () => {
  it("journals a mutation with a monotonic seq and the id as the key", async () => {
    const a = await enqueue({ id: "id-a", ...NUTRITION_POST });
    const b = await enqueue({ id: "id-b", ...NUTRITION_POST });
    expect(b.seq).toBeGreaterThan(a.seq);
    expect(await pendingCount()).toBe(2);
    const pending = await listPending();
    expect(pending.map((r) => r.id)).toEqual(["id-a", "id-b"]); // seq order
  });

  it("refuses a non-replayable mutation (would park forever)", async () => {
    await expect(
      enqueue({ id: "x", kind: "k", endpoint: "e", method: "POST", path: "/api/admin/drugs", body: {} }),
    ).rejects.toThrow(/non-replayable/);
    expect(await pendingCount()).toBe(0);
  });
});

describe("drain", () => {
  it("removes a mutation on a 2xx replay (no data loss, no duplicate)", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    const sent: OutboxRecord[] = [];
    const transport: ReplayTransport = async (r) => {
      sent.push(r);
      return { ok: true, status: 202 };
    };
    const result = await drain(transport, T0);
    expect(result.sent).toBe(1);
    expect(sent[0]!.id).toBe("id-1"); // id forwarded as the Idempotency-Key
    expect(await pendingCount()).toBe(0);
  });

  it("keeps the mutation and backs off on a transient (5xx) failure", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    const transport: ReplayTransport = async () => ({ ok: false, status: 503 });
    const result = await drain(transport, T0, () => 0.5);
    expect(result.failed).toBe(1);
    const row = (await getAll<OutboxRecord>(STORE_OUTBOX))[0]!;
    expect(row.attempts).toBe(1);
    expect(row.parked).toBe(false);
    expect(row.nextAttemptAt).toBeGreaterThan(T0);
    // Not due yet just before its window → the next drain skips it.
    const skipped = await drain(transport, row.nextAttemptAt - 1, () => 0.5);
    expect(skipped.sent + skipped.failed).toBe(0);
  });

  it("parks a mutation on a terminal 4xx (surfaced, not retried)", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    const transport: ReplayTransport = async () => ({ ok: false, status: 422, message: "bad entry" });
    const result = await drain(transport, T0);
    expect(result.parked).toBe(1);
    const row = (await getAll<OutboxRecord>(STORE_OUTBOX))[0]!;
    expect(row.parked).toBe(true);
    expect(row.lastError).toBe("bad entry");
    // Parked rows are invisible to a normal drain.
    const again = await drain(transport, T0 + 9_000_000);
    expect(again.sent + again.failed + again.parked).toBe(0);
  });

  it("treats 429/408 as transient, not terminal", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    const transport: ReplayTransport = async () => ({ ok: false, status: 429 });
    const result = await drain(transport, T0, () => 0.5);
    expect(result.failed).toBe(1);
    expect(result.parked).toBe(0);
  });

  it("re-runs for a mutation enqueued mid-drain instead of dropping the request", async () => {
    // Two rapid checkbox clicks: the second submitMutation enqueues + calls
    // drain() while the first drain is still replaying. The in-flight drain must
    // pick the new row up in a follow-up pass, not leave it queued for 30s.
    await enqueue({ id: "id-a", ...NUTRITION_POST });
    const sent: string[] = [];
    const transport: ReplayTransport = async (r) => {
      sent.push(r.id);
      if (r.id === "id-a") {
        await enqueue({ id: "id-b", ...NUTRITION_POST });
        // The concurrent drain call returns immediately (in-flight guard)...
        expect(await drain(transport, T0)).toEqual({ sent: 0, failed: 0, parked: 0 });
      }
      return { ok: true, status: 202 };
    };
    // ...but the running drain takes another pass and sends the new row too.
    const result = await drain(transport, T0);
    expect(result.sent).toBe(2);
    expect(sent).toEqual(["id-a", "id-b"]);
    expect(await pendingCount()).toBe(0);
  });

  it("treats a 404 on a DELETE as success (target already gone), not a park", async () => {
    await enqueue({
      id: "id-del",
      now: T0,
      kind: "medication.unlogDose",
      endpoint: "medications.adherence.undo",
      method: "DELETE",
      path: "/api/me/medications/m1/adherence/2026-09-17/MORNING",
      body: null,
    });
    const result = await drain(async () => ({ ok: false, status: 404 }), T0);
    expect(result.sent).toBe(1);
    expect(result.parked).toBe(0);
    expect(await pendingCount()).toBe(0);
  });

  it("still parks a 404 on a non-DELETE (the target should exist)", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    const result = await drain(async () => ({ ok: false, status: 404 }), T0);
    expect(result.parked).toBe(1);
    expect(await pendingCount()).toBe(1);
  });

  it("survives a reload: a queued row drains after the connection reopens", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    closeDb(); // simulate a fresh tab reopening the same on-disk database
    expect(await pendingCount()).toBe(1);
    const transport: ReplayTransport = async () => ({ ok: true, status: 202 });
    await drain(transport, T0 + 5000);
    expect(await pendingCount()).toBe(0);
  });
});

describe("rearmAll", () => {
  it("makes parked and backing-off rows due again", async () => {
    await enqueue({ id: "id-1", ...NUTRITION_POST });
    await drain(async () => ({ ok: false, status: 422 }), T0); // park it
    await rearmAll(T0 + 1000);
    const row = (await getAll<OutboxRecord>(STORE_OUTBOX))[0]!;
    expect(row.parked).toBe(false);
    expect(row.attempts).toBe(0);
    expect(row.nextAttemptAt).toBe(T0 + 1000);
  });
});

describe("jitteredBackoffMs", () => {
  it("stays within the ±50% band and respects the ceiling", () => {
    expect(jitteredBackoffMs(1, 0.5)).toBe(2000); // base
    expect(jitteredBackoffMs(1, 0)).toBe(1000); // 0.5×
    expect(jitteredBackoffMs(1, 1)).toBe(3000); // 1.5×
    for (let r = 0; r <= 1.0001; r += 0.1) {
      expect(jitteredBackoffMs(20, r)).toBeLessThanOrEqual(5 * 60 * 1000);
    }
  });
});
