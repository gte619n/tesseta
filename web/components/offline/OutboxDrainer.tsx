"use client";

import { useEffect } from "react";
import { startOutboxDraining } from "@/lib/offline/mutation-client";

// Starts the background outbox drain for the tab: replays any queued mutations
// on reconnect, when the tab regains focus, and on a slow interval. Renders
// nothing. Mounted once inside the global client Providers so a queued write
// (survived a tab close) is retried as soon as the app loads.
export function OutboxDrainer() {
  useEffect(() => startOutboxDraining(), []);
  return null;
}
