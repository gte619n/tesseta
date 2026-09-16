"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { onSynced, startOutboxDraining } from "@/lib/offline/mutation-client";

// Starts the background outbox drain for the tab: replays any queued mutations
// on reconnect, when the tab regains focus, and on a slow interval. Renders
// nothing. Mounted once inside the global client Providers so a queued write
// (survived a tab close) is retried as soon as the app loads.
//
// It also reconciles the server-rendered views: once a drain actually syncs a
// mutation, the server now holds the write, so a router.refresh() swaps each
// client component's optimistic overlay for server truth.
export function OutboxDrainer() {
  const router = useRouter();
  useEffect(() => {
    const stopDraining = startOutboxDraining();
    const stopSynced = onSynced(() => router.refresh());
    return () => {
      stopDraining();
      stopSynced();
    };
  }, [router]);
  return null;
}
