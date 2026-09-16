"use client";

import { usePendingMutations } from "@/lib/offline/use-pending-mutations";

// Unobtrusive indicator that some writes are queued in the outbox and still
// syncing (e.g. logged while offline). Renders nothing when the queue is empty,
// so it never changes the layout in the common case. Fixed bottom-left so it
// doesn't collide with the bottom-right toast stack.
export function PendingBadge() {
  const pending = usePendingMutations();
  if (pending <= 0) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed bottom-4 left-4 z-50 flex items-center gap-2 rounded-full border border-subtle bg-canvas-muted px-3 py-1.5 text-[12px] text-secondary shadow-sm"
    >
      <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-accent-dim" />
      {pending === 1 ? "Syncing 1 change…" : `Syncing ${pending} changes…`}
    </div>
  );
}
