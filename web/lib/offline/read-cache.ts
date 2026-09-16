// Read-through cache for the web client (repeat-view speed + offline reads).
// Backs the second half of the web offline story: pages hydrate instantly from
// the last-seen response and revalidate underneath, and a read still resolves
// (stale) when offline. Data goes through the single typed IndexedDB layer.
//
// This is the data-layer half (like the outbox core in slice 4a). Wiring the SSR
// pages / client components to hydrate from it is the cutover step — see the
// progress notes.

import { STORE_READCACHE, withStore } from "./idb";

interface CacheRecord<T> {
  key: string;
  value: T;
  storedAt: number;
}

/** The current cached value for `key`, or null if absent. */
export async function getCached<T>(key: string): Promise<{ value: T; storedAt: number } | null> {
  const rec = (await withStore(STORE_READCACHE, "readonly", (s) => s.get(key))) as
    | CacheRecord<T>
    | undefined;
  return rec ? { value: rec.value, storedAt: rec.storedAt } : null;
}

/** Overwrite the cached value for `key`. */
export async function putCached<T>(key: string, value: T, now: number = Date.now()): Promise<void> {
  const rec: CacheRecord<T> = { key, value, storedAt: now };
  await withStore(STORE_READCACHE, "readwrite", (s) => s.put(rec));
}

export interface ReadThroughOptions {
  /** Serve the cached value without revalidating when younger than this (ms). */
  maxAgeMs?: number;
  now?: number;
}

export interface ReadThroughResult<T> {
  value: T;
  /** true when served from cache (fresh hit, or a stale fallback after a fetch error). */
  fromCache: boolean;
  /** true when the returned value may be stale (a fetch failed and we fell back). */
  stale: boolean;
}

/**
 * Return the cached value if it's younger than `maxAgeMs`; otherwise fetch,
 * cache, and return fresh. If the fetch fails but a cached value exists, return
 * it stale rather than throwing (offline reads). Throws only when there is
 * neither a fresh cache nor a successful fetch nor any stale fallback.
 */
export async function readThrough<T>(
  key: string,
  fetcher: () => Promise<T>,
  opts: ReadThroughOptions = {},
): Promise<ReadThroughResult<T>> {
  const now = opts.now ?? Date.now();
  const cached = await getCached<T>(key);
  if (cached && opts.maxAgeMs !== undefined && now - cached.storedAt < opts.maxAgeMs) {
    return { value: cached.value, fromCache: true, stale: false };
  }
  try {
    const fresh = await fetcher();
    await putCached(key, fresh, now);
    return { value: fresh, fromCache: false, stale: false };
  } catch (e) {
    if (cached) {
      return { value: cached.value, fromCache: true, stale: true };
    }
    throw e;
  }
}

export interface SwrResult<T> {
  value: T | null;
  storedAt: number | null;
  /** Resolves when the background refresh settles (for tests / await-if-needed). */
  revalidation: Promise<void>;
}

/**
 * Stale-while-revalidate: return whatever is cached (possibly null) at once and
 * kick off a background refresh, invoking `onFresh` if it succeeds. The returned
 * `revalidation` promise lets a caller await the refresh when it needs to
 * (it never rejects — a failed refresh just keeps the cached value).
 */
export async function swr<T>(
  key: string,
  fetcher: () => Promise<T>,
  onFresh?: (value: T) => void,
): Promise<SwrResult<T>> {
  const cached = await getCached<T>(key);
  const revalidation = (async () => {
    try {
      const fresh = await fetcher();
      await putCached(key, fresh);
      onFresh?.(fresh);
    } catch {
      /* offline / failed — keep serving the cached value */
    }
  })();
  return {
    value: cached ? cached.value : null,
    storedAt: cached ? cached.storedAt : null,
    revalidation,
  };
}
