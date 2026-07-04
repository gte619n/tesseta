import { ErrorState } from "@/components/ui/ErrorState";

// App-wide 404. Rendered for unmatched routes and explicit notFound() calls.
export default function NotFound() {
  return (
    <ErrorState
      title="Page not found"
      description="That page doesn't exist or may have moved."
      showHomeLink
    />
  );
}
