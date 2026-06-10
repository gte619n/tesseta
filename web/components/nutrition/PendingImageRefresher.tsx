"use client";

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";

/**
 * While any logged entry's image is still generating ([active] = true), re-fetch
 * the page on a short interval so the studio image appears as soon as it's ready
 * — the web mirror of the Android day-poller. `router.refresh()` re-runs the
 * server component without losing client state, so the attempt counter persists
 * across refreshes and caps the polling if a generation gets stuck.
 */
export function PendingImageRefresher({ active }: { active: boolean }) {
  const router = useRouter();
  const attempts = useRef(0);

  useEffect(() => {
    if (!active) {
      attempts.current = 0;
      return;
    }
    const id = setInterval(() => {
      // ~3 min budget: async photo/describe analysis can take a couple of
      // minutes before the ANALYZING placeholder finalizes (the server fails
      // stale placeholders at 5 min, so polling longer is wasted).
      if (attempts.current >= 60) {
        clearInterval(id);
        return;
      }
      attempts.current += 1;
      router.refresh();
    }, 3000);
    return () => clearInterval(id);
  }, [active, router]);

  return null;
}
