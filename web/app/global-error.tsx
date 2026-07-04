"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/ui/ErrorState";

// Last-resort boundary for errors thrown in the root layout itself. It replaces
// the whole document, so it must render its own <html>/<body>.
export default function GlobalError({
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
    <html lang="en">
      <body className="bg-canvas text-primary">
        <ErrorState onRetry={reset} />
      </body>
    </html>
  );
}
