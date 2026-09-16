// The web mutation outbox — the fix for the top data-loss risk (DL-1): a write
// that fails on a flaky network is journaled to IndexedDB and replayed in the
// background instead of being lost. Mirrors the Android outbox contract: each
// mutation carries a client UUID (= Idempotency-Key), a monotonic seq, and a
// client timestamp; replays are idempotent server-side (backend write contract);
// backoff is exponential with jitter; the queue survives a tab close.

import {
  getAll,
  OutboxRecord,
  STORE_OUTBOX,
  withStore,
} from "./idb";
import { isReplayable } from "./replay-endpoints";

const BASE_BACKOFF_MS = 2_000; // 2s
const MAX_BACKOFF_MS = 5 * 60 * 1000; // 5m ceiling (web sessions are short-lived)

/** Exponential backoff with full ±50% jitter, matching the Android policy. */
export function jitteredBackoffMs(attempts: number, rand = Math.random()): number {
  const exp = BASE_BACKOFF_MS * 2 ** Math.min(Math.max(attempts - 1, 0), 20);
  const base = Math.min(exp, MAX_BACKOFF_MS);
  const factor = 0.5 + Math.min(Math.max(rand, 0), 1); // [0.5, 1.5]
  return Math.min(Math.round(base * factor), MAX_BACKOFF_MS);
}

let seqCounter = 0;

/** Next monotonic seq (max existing + 1), so replay order survives reloads. */
async function nextSeq(): Promise<number> {
  const all = await getAll<OutboxRecord>(STORE_OUTBOX);
  const maxSeq = all.reduce((m, r) => Math.max(m, r.seq), 0);
  seqCounter = Math.max(seqCounter, maxSeq) + 1;
  return seqCounter;
}

export interface EnqueueInput {
  id: string; // client UUID = Idempotency-Key
  kind: string;
  endpoint: string;
  method: OutboxRecord["method"];
  path: string;
  body: unknown;
  now?: number;
}

/** Journal a mutation. The caller has already applied the optimistic UI update. */
export async function enqueue(input: EnqueueInput): Promise<OutboxRecord> {
  if (!isReplayable(input.method, input.path)) {
    // Fail fast in dev: a mutation the replay proxy will reject 400 must never
    // enter the queue (it would park forever).
    throw new Error(`refusing to enqueue non-replayable ${input.method} ${input.path}`);
  }
  const now = input.now ?? Date.now();
  const record: OutboxRecord = {
    id: input.id,
    seq: await nextSeq(),
    kind: input.kind,
    endpoint: input.endpoint,
    method: input.method,
    path: input.path,
    body: input.body,
    createdAt: now,
    attempts: 0,
    nextAttemptAt: now,
    parked: false,
  };
  await withStore(STORE_OUTBOX, "readwrite", (s) => s.put(record));
  return record;
}

/** Every queued mutation, oldest-first by seq. */
export async function listPending(): Promise<OutboxRecord[]> {
  const all = await getAll<OutboxRecord>(STORE_OUTBOX);
  return all.sort((a, b) => a.seq - b.seq);
}

/** Count of not-yet-synced mutations (drives the pending badge). */
export async function pendingCount(): Promise<number> {
  return (await getAll<OutboxRecord>(STORE_OUTBOX)).length;
}

async function remove(id: string): Promise<void> {
  await withStore(STORE_OUTBOX, "readwrite", (s) => s.delete(id));
}

async function put(record: OutboxRecord): Promise<void> {
  await withStore(STORE_OUTBOX, "readwrite", (s) => s.put(record));
}

/** How the drain talks to the network. Injectable so tests avoid real fetch. */
export interface ReplayTransport {
  (record: OutboxRecord): Promise<{ ok: boolean; status: number; message?: string }>;
}

/** Default transport: POST the record to the same-origin authenticated proxy. */
export const httpReplayTransport: ReplayTransport = async (record) => {
  const res = await fetch("/api/outbox/replay", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      id: record.id,
      method: record.method,
      path: record.path,
      body: record.body,
    }),
  });
  let message: string | undefined;
  if (!res.ok) {
    message = await res.text().catch(() => undefined);
  }
  return { ok: res.ok, status: res.status, message };
};

export interface DrainResult {
  sent: number;
  failed: number;
  parked: number;
}

/**
 * Replay every due mutation in seq order. A 2xx clears the row; a terminal 4xx
 * (except 408/429) parks it out of the auto-drain (surfaced in the failed UI); a
 * transient failure backs off with jitter. Safe to call concurrently — a simple
 * in-flight guard prevents overlapping drains double-sending.
 */
let draining = false;
export async function drain(
  transport: ReplayTransport = httpReplayTransport,
  now: number = Date.now(),
  rand: () => number = Math.random,
): Promise<DrainResult> {
  if (draining) return { sent: 0, failed: 0, parked: 0 };
  draining = true;
  try {
    const due = (await listPending()).filter((r) => !r.parked && r.nextAttemptAt <= now);
    let sent = 0;
    let failed = 0;
    let parked = 0;
    for (const record of due) {
      let result: { ok: boolean; status: number; message?: string };
      try {
        result = await transport(record);
      } catch (e) {
        result = { ok: false, status: 0, message: (e as Error).message };
      }
      if (result.ok) {
        await remove(record.id);
        sent++;
        continue;
      }
      const terminal = result.status >= 400 && result.status < 500
        && result.status !== 408 && result.status !== 429;
      if (terminal) {
        await put({ ...record, parked: true, lastError: result.message ?? `HTTP ${result.status}` });
        parked++;
      } else {
        const attempts = record.attempts + 1;
        await put({
          ...record,
          attempts,
          nextAttemptAt: now + jitteredBackoffMs(attempts, rand()),
          lastError: result.message ?? `HTTP ${result.status}`,
        });
        failed++;
      }
    }
    return { sent, failed, parked };
  } finally {
    draining = false;
  }
}

/** Manual retry (user tapped "retry"): re-arm parked + backing-off rows. */
export async function rearmAll(now: number = Date.now()): Promise<void> {
  const all = await getAll<OutboxRecord>(STORE_OUTBOX);
  for (const r of all) {
    await put({ ...r, parked: false, attempts: 0, nextAttemptAt: now });
  }
}
