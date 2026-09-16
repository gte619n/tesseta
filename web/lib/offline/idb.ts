// The single typed IndexedDB access layer for the web client (offline contract:
// "IndexedDB via a single typed data layer"). Everything that touches IndexedDB
// goes through here — no component or feature module opens a database directly.
//
// Today it backs the mutation outbox (closing the web data-loss hole: a write
// that fails on a flaky network is no longer lost). The read-through cache
// (later slice) will add its own object store alongside `outbox` under the same
// version-bumped schema.

const DB_NAME = "tesseta-offline";
const DB_VERSION = 2;

/** Object store names. Add here + bump DB_VERSION + handle in onupgradeneeded. */
export const STORE_OUTBOX = "outbox";
/** Read-through cache store (repeat-view speed + offline reads). */
export const STORE_READCACHE = "readcache";

/** One queued mutation. `id` doubles as the Idempotency-Key sent on replay. */
export interface OutboxRecord {
  /** Client-generated UUID; also the Idempotency-Key header on replay. */
  id: string;
  /** Monotonic per-device sequence for stable replay ordering. */
  seq: number;
  /** Logical mutation kind, e.g. "nutrition.addEntry" (for optimistic routing). */
  kind: string;
  /** Allowlisted replay descriptor: which backend write this maps to. */
  endpoint: string;
  /** HTTP method the replay proxy will use. */
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  /** Concrete backend path (already interpolated), e.g. /api/me/nutrition/2026-09-16/entries. */
  path: string;
  /** JSON request body, or null for bodyless writes. */
  body: unknown;
  /** Client timestamp (ms) when enqueued. */
  createdAt: number;
  /** Replay attempts so far (drives backoff). */
  attempts: number;
  /** Epoch ms before which the drain must not retry this row (backoff). */
  nextAttemptAt: number;
  /** Set when a terminal (4xx) rejection parks the row out of the auto-drain. */
  parked?: boolean;
  /** Last error message, surfaced in the pending/failed UI. */
  lastError?: string;
}

let dbPromise: Promise<IDBDatabase> | null = null;
let openDbHandle: IDBDatabase | null = null;

/** Opens (once) the shared database, creating stores on first run / upgrade. */
export function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("IndexedDB is unavailable in this environment"));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_OUTBOX)) {
        const store = db.createObjectStore(STORE_OUTBOX, { keyPath: "id" });
        // Drain reads in seq order; the index keeps that cheap.
        store.createIndex("seq", "seq", { unique: false });
        store.createIndex("nextAttemptAt", "nextAttemptAt", { unique: false });
      }
      // v2: additive read-through cache store (never drops the outbox).
      if (!db.objectStoreNames.contains(STORE_READCACHE)) {
        db.createObjectStore(STORE_READCACHE, { keyPath: "key" });
      }
    };
    req.onsuccess = () => {
      openDbHandle = req.result;
      resolve(req.result);
    };
    req.onerror = () => reject(req.error ?? new Error("failed to open IndexedDB"));
  });
  return dbPromise;
}

/** Closes the cached connection (sign-out, or between tests so a delete unblocks). */
export function closeDb(): void {
  openDbHandle?.close();
  openDbHandle = null;
  dbPromise = null;
}

/** Promisifies a single-store transaction against `store`. */
export async function withStore<T>(
  store: string,
  mode: IDBTransactionMode,
  fn: (store: IDBObjectStore) => IDBRequest<T> | void,
): Promise<T | undefined> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode);
    const os = tx.objectStore(store);
    let request: IDBRequest<T> | void;
    try {
      request = fn(os);
    } catch (e) {
      reject(e);
      return;
    }
    tx.oncomplete = () => resolve(request ? request.result : undefined);
    tx.onabort = () => reject(tx.error ?? new Error("transaction aborted"));
    tx.onerror = () => reject(tx.error ?? new Error("transaction failed"));
  });
}

/** Reads every record from `store` (used by the drain + the pending-count hook). */
export async function getAll<T>(store: string): Promise<T[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readonly");
    const req = tx.objectStore(store).getAll();
    req.onsuccess = () => resolve(req.result as T[]);
    req.onerror = () => reject(req.error ?? new Error("getAll failed"));
  });
}

