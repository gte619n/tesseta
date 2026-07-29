"use client";

import { useEffect } from "react";

// Records the browser's IANA time zone in a `tz` cookie so the server-side
// `apiFetch` can forward it to the backend as `X-Timezone`. The Next.js server
// runs the fetch, so without this the backend would see the server's own (UTC)
// zone and "today" endpoints (e.g. /medications/today) would roll over at the
// wrong local midnight. Set once on mount; refreshed if the zone changes.
export function TimezoneCookie() {
  useEffect(() => {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (tz) {
      document.cookie = `tz=${encodeURIComponent(tz)}; path=/; max-age=31536000; SameSite=Lax`;
    }
  }, []);
  return null;
}
