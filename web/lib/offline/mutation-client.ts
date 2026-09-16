// Client-facing entry point for offline-safe writes. A client component calls
// `submitMutation(...)` instead of invoking a bare server action; the mutation
// is journaled to the IndexedDB outbox first (so a flaky network can't lose it),
// then a background drain replays it through the authenticated proxy. Callers
// apply their own optimistic UI update before/after enqueuing.

import { drain, enqueue, EnqueueInput, pendingCount } from "./outbox";

/** Generates a client mutation id (also the Idempotency-Key). */
function newId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  // Fallback for environments without crypto.randomUUID (shouldn't happen in a
  // modern browser); good enough for a client-unique key.
  return `m-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

type Listener = () => void;
const listeners = new Set<Listener>();

/** Notify subscribers (the pending badge) that the queue changed. */
function notify(): void {
  listeners.forEach((l) => l());
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export interface SubmitInput {
  kind: string;
  endpoint: string;
  method: EnqueueInput["method"];
  path: string;
  body?: unknown;
}

/**
 * Enqueue a mutation and kick off a drain. Resolves once the mutation is
 * durably journaled (NOT once it reaches the server) — the UI must already
 * reflect it optimistically. Returns the client id (= Idempotency-Key).
 */
export async function submitMutation(input: SubmitInput): Promise<string> {
  const id = newId();
  await enqueue({
    id,
    kind: input.kind,
    endpoint: input.endpoint,
    method: input.method,
    path: input.path,
    body: input.body ?? null,
  });
  notify();
  // Fire-and-forget: the write is safe the moment it's journaled.
  void drainNow();
  return id;
}

/** Subscribers notified after a drain that actually synced ≥1 mutation. Used to
 *  reconcile server-rendered views (router.refresh) once the server has the write. */
const syncedListeners = new Set<Listener>();

export function onSynced(listener: Listener): () => void {
  syncedListeners.add(listener);
  return () => syncedListeners.delete(listener);
}

/** Runs a drain and notifies subscribers of the resulting queue change. */
export async function drainNow(): Promise<void> {
  try {
    const result = await drain();
    if (result.sent || result.failed || result.parked) notify();
    // A successful sync means the server now holds these writes — let SSR views
    // reconcile (drop their optimistic overlay for server truth).
    if (result.sent > 0) syncedListeners.forEach((l) => l());
  } catch {
    // Drain failures are self-healing (rows stay queued); nothing to surface here.
  }
}

export { pendingCount };

let started = false;

/**
 * Install the background drain triggers once per tab: on reconnect, when the tab
 * becomes visible, and on a slow interval as a floor. Idempotent.
 */
export function startOutboxDraining(intervalMs = 30_000): () => void {
  if (started || typeof window === "undefined") return () => {};
  started = true;
  const onOnline = () => void drainNow();
  const onVisible = () => {
    if (document.visibilityState === "visible") void drainNow();
  };
  window.addEventListener("online", onOnline);
  document.addEventListener("visibilitychange", onVisible);
  const timer = window.setInterval(() => void drainNow(), intervalMs);
  // Attempt an initial drain in case rows survived a previous session.
  void drainNow();
  return () => {
    window.removeEventListener("online", onOnline);
    document.removeEventListener("visibilitychange", onVisible);
    window.clearInterval(timer);
    started = false;
  };
}
