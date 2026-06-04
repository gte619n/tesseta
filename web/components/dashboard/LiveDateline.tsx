"use client";

import { useEffect, useState } from "react";

// The dashboard header's date/time line. Rendered client-side so it reflects
// the user's own clock and timezone (the server runs UTC on Cloud Run). Stays
// blank until mounted to avoid a hydration mismatch, then ticks each minute.
export function LiveDateline() {
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    setNow(new Date());
    const id = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(id);
  }, []);

  if (!now) {
    // Reserve the line height before the clock mounts.
    return <span aria-hidden>&nbsp;</span>;
  }

  const weekday = now
    .toLocaleDateString("en-US", { weekday: "short" })
    .toUpperCase();
  const date = now
    .toLocaleDateString("en-US", { month: "short", day: "numeric" })
    .toUpperCase();
  const time = now.toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });

  return (
    <span>
      {weekday} · {date} · {time}
    </span>
  );
}
