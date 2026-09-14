// OBS-002 — ship a caught client error to the same-app log route so it lands in
// Cloud Logging. Used by the App Router error boundaries. Best-effort and
// non-throwing: reporting must never itself crash the recovery UI. `keepalive`
// lets the POST survive an immediate navigation/reload after the error.
export function reportClientError(error: Error & { digest?: string }): void {
  try {
    void fetch("/api/log-client-error", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      keepalive: true,
      body: JSON.stringify({
        // Only the error shape + location — never form values or user identity.
        message: error.message,
        stack: error.stack,
        digest: error.digest,
        pathname:
          typeof window !== "undefined" ? window.location.pathname : undefined,
      }),
    }).catch(() => {});
  } catch {
    // Serialization or fetch construction failed — swallow; the UI still shows.
  }
}
