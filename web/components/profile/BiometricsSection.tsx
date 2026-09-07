"use client";

import { useState, useTransition } from "react";
import { useUnits } from "@/components/ui/UnitsProvider";
import type { BiometricSummary } from "@/lib/biometrics-api";
import { formatCadence } from "@/lib/format-cadence";
import { formatObserved } from "@/lib/format-observed";
import { formatNumber } from "@/lib/format-number";
import { weightUnitLabel, weightValue } from "@/lib/units";
import type { WeightUnit } from "@/lib/units";

const KG_TO_LB = 2.20462;

// Per-metric show/hide with the latest reading + how often it arrives. Toggling
// optimistically updates the switch and persists the full hidden set via the
// server action (which revalidates the dashboard so hidden cards drop).
export function BiometricsSection({
  summaries,
  saveAction,
}: {
  summaries: BiometricSummary[];
  saveAction: (hidden: string[]) => Promise<void>;
}) {
  const { prefs } = useUnits();
  const [hidden, setHidden] = useState<Set<string>>(
    () => new Set(summaries.filter((s) => !s.visible).map((s) => s.key)),
  );
  const [pending, startTransition] = useTransition();

  function toggle(key: string) {
    const next = new Set(hidden);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setHidden(next);
    startTransition(async () => {
      try {
        await saveAction([...next]);
      } catch {
        // Revert on failure.
        setHidden(hidden);
      }
    });
  }

  return (
    <div>
      {summaries.map((s) => {
        const visible = !hidden.has(s.key);
        return (
          <div
            key={s.key}
            className="flex items-center justify-between gap-3 border-b-[0.5px] border-border-default py-2.5 last:border-b-0"
          >
            <div className="min-w-0">
              <div className="text-[13px] text-primary">{s.label}</div>
              <div className="mt-0.5 font-mono text-[11px] leading-[1.5] text-tertiary tabular">
                {formatLatest(s, prefs.weight)}
                {" · "}
                {s.latestAt ? formatObserved(s.latestAt) : "no data"}
                {" · "}
                {formatCadence(s.avgIntervalDays)}
              </div>
            </div>
            <Switch checked={visible} disabled={pending} onToggle={() => toggle(s.key)} />
          </div>
        );
      })}
    </div>
  );
}

function formatLatest(s: BiometricSummary, weightUnit: WeightUnit): string {
  if (s.latestValue == null) return "—";
  switch (s.key) {
    case "WEIGHT":
      return `${weightValue(s.latestValue * KG_TO_LB, weightUnit).toFixed(1)} ${weightUnitLabel(weightUnit)}`;
    case "BODY_FAT":
      return `${s.latestValue.toFixed(1)}%`;
    case "SLEEP":
      return `${(s.latestValue / 60).toFixed(1)} h`;
    case "STEPS":
      return `${formatNumber(s.latestValue, 0)} steps`;
    case "RESTING_HR":
      return `${Math.round(s.latestValue)} bpm`;
    case "HRV":
      return `${Math.round(s.latestValue)} ms`;
    default:
      return `${s.latestValue}${s.unit ? ` ${s.unit}` : ""}`;
  }
}

function Switch({
  checked,
  disabled,
  onToggle,
}: {
  checked: boolean;
  disabled: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={onToggle}
      className={`relative h-[18px] w-8 shrink-0 cursor-pointer rounded-full transition-colors ${
        checked ? "bg-accent" : "bg-border-default"
      } ${disabled ? "opacity-60" : ""}`}
    >
      <span
        className={`absolute top-[2px] h-[14px] w-[14px] rounded-full bg-surface shadow-sm transition-all ${
          checked ? "left-[16px]" : "left-[2px]"
        }`}
      />
    </button>
  );
}
