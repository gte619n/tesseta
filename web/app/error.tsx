"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ui/ErrorState";

// Root route-segment error boundary. A thrown error in any page below (e.g. a
// backend 500 from apiFetch) renders this recovery card in place of the app
// shell crashing to Next's default screen. `reset()` re-renders the segment.
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return <ErrorState onRetry={reset} />;
}
