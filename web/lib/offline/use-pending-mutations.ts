"use client";

import { useEffect, useState } from "react";
import { pendingCount, subscribe } from "./mutation-client";

/**
 * Reactive count of queued (not-yet-synced) mutations. Backs the pending badge.
 * Re-reads on every outbox change (enqueue / drain) via the mutation-client
 * subscription. Returns 0 during SSR and before the first read.
 */
export function usePendingMutations(): number {
  const [count, setCount] = useState(0);

  useEffect(() => {
    let active = true;
    const refresh = () => {
      pendingCount()
        .then((n) => {
          if (active) setCount(n);
        })
        .catch(() => {
          /* IndexedDB unavailable — treat as nothing pending. */
        });
    };
    refresh();
    const unsubscribe = subscribe(refresh);
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  return count;
}
