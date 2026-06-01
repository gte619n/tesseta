"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import { weightUnitLabel, weightValue } from "@/lib/units";

// Client helpers for the body-composition page, which is otherwise a
// server component. They format canonical lb values per the user's
// weight preference (defaulting to lb so SSR and first render match).

// Big metric-card value: "<number>" + a styled unit span. Mirrors the
// MetricCard value markup so weight/lean cards stay visually identical.
export function BodyWeightMetricValue({ lb }: { lb: number | null }) {
  const { prefs } = useUnits();
  const unit = prefs.weight;
  return (
    <>
      {lb !== null ? weightValue(lb, unit).toFixed(1) : "—"}
      <span className="ml-1 text-[14px] text-secondary">
        {weightUnitLabel(unit)}
      </span>
    </>
  );
}

// Table cell content: "<number>" + a tertiary unit span, or an em dash.
export function BodyWeightCell({ lb }: { lb: number | null }) {
  const { prefs } = useUnits();
  const unit = prefs.weight;
  if (lb === null) return <span className="text-tertiary">—</span>;
  return (
    <>
      {weightValue(lb, unit).toFixed(1)}
      <span className="ml-1 text-tertiary">{weightUnitLabel(unit)}</span>
    </>
  );
}
