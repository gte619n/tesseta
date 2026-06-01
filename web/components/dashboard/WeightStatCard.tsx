"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import { Sparkline } from "@/components/dashboard/StatCard";
import { weightUnitLabel, weightValue } from "@/lib/units";

// Client variant of the Weight StatCard. Identical markup to StatCard's
// weight slot, but value/unit/delta are formatted from canonical lb
// numbers using the user's weight preference. Other stat cards stay
// server-rendered through StatCard.
export type WeightStat = {
  label: string;
  icon: string;
  sparkline: string;
  valueLb: number;
  delta?: {
    // Signed 7-day change in lb (negative = weight loss).
    deltaLb: number;
    window: string;
    tone: "good" | "alert";
  };
};

export function WeightStatCard({ stat }: { stat: WeightStat }) {
  const { prefs } = useUnits();
  const unit = prefs.weight;
  const value = weightValue(stat.valueLb, unit).toFixed(1);

  return (
    <div className="rounded-[9px] border-[0.5px] border-border-default bg-surface px-3.5 pb-[11px] pt-[13px]">
      <div className="mb-1.5 flex items-center justify-between">
        <span className="caps-mono text-[10px] text-tertiary">{stat.label}</span>
        <i
          className={`ti ti-${stat.icon} text-[12px] text-quaternary`}
          aria-hidden
        />
      </div>
      <div className="font-mono text-[22px] font-medium leading-[1.1] tracking-[-0.02em] text-primary tabular">
        {value}
        <span className="ml-[3px] text-[11px] font-normal text-tertiary">
          {weightUnitLabel(unit)}
        </span>
      </div>
      <div className="mt-1.5 flex items-center justify-between">
        {stat.delta ? (
          <span
            className={`font-mono text-[10px] font-medium tabular ${
              stat.delta.tone === "good" ? "text-good" : "text-alert"
            }`}
          >
            {stat.delta.deltaLb < 0 ? "↓" : "↑"}{" "}
            {Math.abs(weightValue(stat.delta.deltaLb, unit)).toFixed(1)}{" "}
            {stat.delta.window}
          </span>
        ) : (
          <span />
        )}
        <Sparkline points={stat.sparkline} />
      </div>
    </div>
  );
}
