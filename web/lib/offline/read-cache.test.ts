import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { closeDb } from "./idb";
import { getCached, putCached, readThrough, swr } from "./read-cache";

async function wipe() {
  closeDb();
  await new Promise<void>((resolve) => {
    const req = indexedDB.deleteDatabase("tesseta-offline");
    req.onsuccess = () => resolve();
    req.onerror = () => resolve();
    req.onblocked = () => resolve();
  });
}

beforeEach(wipe);
afterEach(wipe);

describe("readThrough", () => {
  it("fetches and caches on a cold key", async () => {
    const fetcher = vi.fn().mockResolvedValue({ n: 1 });
    const r = await readThrough("k", fetcher, { maxAgeMs: 1000, now: 100 });
    expect(r.value).toEqual({ n: 1 });
    expect(r.fromCache).toBe(false);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect((await getCached("k"))?.value).toEqual({ n: 1 });
  });

  it("serves the cache without fetching while fresh", async () => {
    await putCached("k", { n: 1 }, 100);
    const fetcher = vi.fn().mockResolvedValue({ n: 2 });
    const r = await readThrough("k", fetcher, { maxAgeMs: 1000, now: 500 });
    expect(r.value).toEqual({ n: 1 });
    expect(r.fromCache).toBe(true);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("revalidates once the cache is older than maxAge", async () => {
    await putCached("k", { n: 1 }, 100);
    const fetcher = vi.fn().mockResolvedValue({ n: 2 });
    const r = await readThrough("k", fetcher, { maxAgeMs: 1000, now: 2000 });
    expect(r.value).toEqual({ n: 2 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("falls back to a stale cache when the fetch fails (offline read)", async () => {
    await putCached("k", { n: 1 }, 100);
    const fetcher = vi.fn().mockRejectedValue(new Error("offline"));
    const r = await readThrough("k", fetcher, { maxAgeMs: 1000, now: 5000 });
    expect(r.value).toEqual({ n: 1 });
    expect(r.stale).toBe(true);
  });

  it("throws when there is neither cache nor a successful fetch", async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error("offline"));
    await expect(readThrough("k", fetcher, { maxAgeMs: 1000 })).rejects.toThrow("offline");
  });
});

describe("swr", () => {
  it("returns cached immediately and refreshes in the background", async () => {
    await putCached("k", { n: 1 }, 100);
    let fresh: unknown = null;
    const immediate = await swr("k", async () => ({ n: 2 }), (v) => (fresh = v));
    expect(immediate.value).toEqual({ n: 1 }); // painted from cache at once
    // Let the background refresh settle.
    await new Promise((r) => setTimeout(r, 0));
    expect(fresh).toEqual({ n: 2 });
    expect((await getCached("k"))?.value).toEqual({ n: 2 });
  });

  it("returns null on a cold key but still populates the cache", async () => {
    const immediate = await swr("cold", async () => ({ n: 9 }));
    expect(immediate.value).toBeNull();
    await new Promise((r) => setTimeout(r, 0));
    expect((await getCached("cold"))?.value).toEqual({ n: 9 });
  });
});
