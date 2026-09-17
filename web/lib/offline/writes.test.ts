import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { closeDb } from "./idb";
import { listPending } from "./outbox";
import { isReplayable } from "./replay-endpoints";
import {
  addEntryOffline,
  deleteEntryOffline,
  logDoseOffline,
  logSessionOffline,
  unlogDoseOffline,
  updateEntryOffline,
} from "./writes";

// The web write-path cutover (slice 4b): each UI write now journals through the
// outbox instead of awaiting a server action. These prove the three cut-over
// paths produce a correctly-targeted, replayable mutation — i.e. the exact
// backend endpoint the proxy allowlist accepts, with the client id round-tripped.
// The drain/replay/backoff behaviour itself is covered by outbox.test.ts.

async function wipe() {
  closeDb();
  await new Promise<void>((resolve) => {
    const req = indexedDB.deleteDatabase("tesseta-offline");
    req.onsuccess = () => resolve();
    req.onerror = () => resolve();
    req.onblocked = () => resolve();
  });
}

beforeEach(() => {
  // The drain fires a background fetch to the proxy; there's no server in jsdom,
  // so stub it as offline. The mutation stays queued (that's the point).
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
  return wipe();
});
afterEach(async () => {
  vi.unstubAllGlobals();
  await wipe();
});

describe("nutrition writes", () => {
  it("addEntryOffline journals a replayable POST carrying the client id", async () => {
    await addEntryOffline("2026-09-16", {
      id: "entry-1",
      meal: "LUNCH",
      foodId: null,
      foodName: "Eggs",
      servingLabel: "2 eggs",
      servingGrams: 100,
      quantity: 1,
      macros: { caloriesKcal: 150, proteinGrams: 12, carbsGrams: 1, fatGrams: 10 },
      source: "MANUAL",
    });
    const [row] = await listPending();
    expect(row!.method).toBe("POST");
    expect(row!.path).toBe("/api/me/nutrition/2026-09-16/entries");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
    expect((row!.body as { id: string }).id).toBe("entry-1");
  });

  it("updateEntryOffline journals a replayable PATCH on the entry", async () => {
    await updateEntryOffline("2026-09-16", "e9", { meal: "DINNER" });
    const [row] = await listPending();
    expect(row!.method).toBe("PATCH");
    expect(row!.path).toBe("/api/me/nutrition/2026-09-16/entries/e9");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
  });

  it("deleteEntryOffline journals a replayable DELETE on the entry", async () => {
    await deleteEntryOffline("2026-09-16", "e9");
    const [row] = await listPending();
    expect(row!.method).toBe("DELETE");
    expect(row!.path).toBe("/api/me/nutrition/2026-09-16/entries/e9");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
  });
});

describe("medication + workout writes", () => {
  it("logDoseOffline journals a replayable adherence POST carrying the local date", async () => {
    await logDoseOffline("med-1", "MORNING", "2026-09-17");
    const [row] = await listPending();
    expect(row!.method).toBe("POST");
    expect(row!.path).toBe("/api/me/medications/med-1/adherence");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
    expect(row!.body).toEqual({ window: "MORNING", date: "2026-09-17" });
  });

  it("unlogDoseOffline journals a replayable adherence DELETE (uncheck)", async () => {
    await unlogDoseOffline("med-1", "2026-09-17", "MORNING");
    const [row] = await listPending();
    expect(row!.method).toBe("DELETE");
    expect(row!.path).toBe("/api/me/medications/med-1/adherence/2026-09-17/MORNING");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
  });

  it("logSessionOffline journals a replayable session PUT", async () => {
    await logSessionOffline("wp_1", "2026-09-16_dayA", { status: "COMPLETED", logged: [] });
    const [row] = await listPending();
    expect(row!.method).toBe("PUT");
    expect(row!.path).toBe("/api/me/workout-programs/wp_1/sessions/2026-09-16_dayA");
    expect(isReplayable(row!.method, row!.path)).toBe(true);
  });
});

describe("every cutover write is on the proxy allowlist", () => {
  it("has no path that the replay proxy would reject", async () => {
    await addEntryOffline("2026-09-16", { id: "x" });
    await updateEntryOffline("2026-09-16", "e1", {});
    await deleteEntryOffline("2026-09-16", "e1");
    await logDoseOffline("m1", "NOON");
    await unlogDoseOffline("m1", "2026-09-17", "NOON");
    await logSessionOffline("wp_1", "s1", {});
    const rows = await listPending();
    expect(rows).toHaveLength(6);
    for (const r of rows) {
      expect(isReplayable(r.method, r.path)).toBe(true);
    }
  });
});
