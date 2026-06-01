"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import { weightUnitLabel, weightValue } from "@/lib/units";

// Renders a canonical lb weight as "<number><unit>" using the user's
// weight preference. Defaults to lb so server output and first client
// render match. `unitClassName` styles the trailing unit span to match
// whatever surface it's dropped into.
export function WeightValue({
  lb,
  decimals = 1,
  unitClassName,
}: {
  lb: number;
  decimals?: number;
  unitClassName?: string;
}) {
  const { prefs } = useUnits();
  const unit = prefs.weight;
  return (
    <>
      {weightValue(lb, unit).toFixed(decimals)}
      <span className={unitClassName}>{weightUnitLabel(unit)}</span>
    </>
  );
}
