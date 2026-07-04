"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ui/ErrorState";

// Error boundary for the authenticated /me area so one failed data load (blood,
// meds, dashboard, etc.) shows a recovery card instead of blanking the page.
export default function MeError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <ErrorState
      description="We couldn't load your data right now. Please try again."
      onRetry={reset}
    />
  );
}
