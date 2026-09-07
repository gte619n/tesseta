import { formatObserved } from "@/lib/format-observed";

// Subtle "when the latest reading was recorded" line at the foot of a metric
// card. Shared by the server-rendered StatCard and the client WeightStatCard.
// Accepts a YYYY-MM-DD date (daily metrics) or an ISO timestamp (weigh-ins).
export function ObservedLabel({ observedAt }: { observedAt?: string }) {
  if (!observedAt) return null;
  return (
    <div className="mt-1.5 font-mono text-[9px] text-quaternary tabular">
      {formatObserved(observedAt)}
    </div>
  );
}
