"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ui/ErrorState";

// Error boundary for the admin area (drug/exercise/equipment catalogs).
export default function AdminError({
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
      title="Admin action failed"
      description="Something went wrong loading this admin view. Please try again."
      onRetry={reset}
    />
  );
}
