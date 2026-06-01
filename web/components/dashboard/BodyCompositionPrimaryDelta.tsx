"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import { weightUnitLabel, weightValue } from "@/lib/units";

// The body-composition "primary" weight delta (e.g. "↓ 3.6 lb vs 90d
// avg"), formatted from a canonical signed lb delta using the user's
// weight preference.
export function BodyCompositionPrimaryDelta({
  deltaLb,
  suffix,
}: {
  deltaLb: number;
  suffix: string;
}) {
  const { prefs } = useUnits();
  const unit = prefs.weight;
  const magnitude = Math.abs(weightValue(deltaLb, unit));

  const text =
    Math.abs(deltaLb) < 0.05
      ? `unchanged ${suffix}`
      : `${deltaLb < 0 ? "↓" : "↑"} ${magnitude.toFixed(1)} ${weightUnitLabel(unit)} ${suffix}`;

  return <div className="caps-mono mt-[5px] text-[10px] text-good">{text}</div>;
}
