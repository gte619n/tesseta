import Link from "next/link";

type ErrorStateProps = {
  title?: string;
  description?: string;
  /** When provided, renders a "Try again" button (used by error boundaries). */
  onRetry?: () => void;
  /** When true, renders a link back to the dashboard (used by not-found). */
  showHomeLink?: boolean;
};

/**
 * On-brand recovery card for error boundaries and not-found. Presentational and
 * server-compatible: the retry button only appears when `onRetry` is supplied
 * (from a client error boundary). `role="alert"` announces it to assistive tech.
 */
export function ErrorState({
  title = "Something went wrong",
  description = "We couldn't load this content. Please try again.",
  onRetry,
  showHomeLink,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className="mx-auto my-16 max-w-md rounded-2xl border border-border-default bg-surface p-8 text-center"
    >
      <h2 className="text-lg font-semibold text-primary">{title}</h2>
      <p className="mt-2 text-sm text-secondary">{description}</p>
      <div className="mt-6 flex items-center justify-center gap-3">
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-full bg-accent px-5 py-2 text-sm font-medium text-inverse hover:bg-accent-dim"
          >
            Try again
          </button>
        )}
        {showHomeLink && (
          <Link
            href="/"
            className="rounded-full border border-border-default px-5 py-2 text-sm font-medium text-primary hover:bg-canvas-muted"
          >
            Back to dashboard
          </Link>
        )}
      </div>
    </div>
  );
}
