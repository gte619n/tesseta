"use client";

import type { Comparator } from "@/lib/types/goals";
import { COMPARATOR_SYMBOL } from "@/lib/types/goals";
import { formatNumber } from "@/lib/format-number";

// A mini horizontal bar showing a current value relative to a single
// target threshold with a comparator (e.g. "LDL 112 → target < 100").
// Generalized from the blood RangeBar: instead of a low/high reference
// band it marks one target line, shades the "satisfied" side, and colors
// the value dot good / warn / alert by how the value sits against target.
//
// Pure presentation, no data fetching — safe to render inside any client
// tree. Used for metric-bound Goal step readouts.

type Status = "good" | "warn" | "alert";

export type RangeIndicatorProps = {
  current: number | null;
  target: number;
  comparator: Comparator;
  unit?: string;
  // When the value previously crossed the target but has since regressed
  // back across it, the bar tints alert regardless of the raw comparison
  // (the step stays done; this surfaces the divergence).
  regressed?: boolean;
  className?: string;
};

function satisfies(value: number, target: number, cmp: Comparator): boolean {
  switch (cmp) {
    case "LT":
      return value < target;
    case "LTE":
      return value <= target;
    case "GT":
      return value > target;
    case "GTE":
      return value >= target;
    case "EQ":
      return value === target;
  }
}

function lowerIsBetter(cmp: Comparator): boolean {
  return cmp === "LT" || cmp === "LTE";
}

function statusFor(
  value: number,
  target: number,
  cmp: Comparator,
  regressed: boolean,
): Status {
  if (regressed) return "alert";
  if (satisfies(value, target, cmp)) return "good";
  // Not yet met — gauge how far off. Within 15% of target is "warn"
  // (almost there), beyond that is "alert".
  const denom = Math.abs(target) || 1;
  const off = Math.abs(value - target) / denom;
  return off <= 0.15 ? "warn" : "alert";
}

export function RangeIndicator({
  current,
  target,
  comparator,
  unit,
  regressed = false,
  className,
}: RangeIndicatorProps) {
  if (current === null || !Number.isFinite(current)) {
    return (
      <span className={`font-mono text-[10px] text-tertiary ${className ?? ""}`}>
        — → {comparatorLabel(comparator, target, unit)}
      </span>
    );
  }

  const status = statusFor(current, target, comparator, regressed);

  // Build a display window centered so both the value and the target are
  // visible with padding. For LT/LTE the "good" zone shades left of the
  // target; for GT/GTE/EQ it shades right.
  const lo = Math.min(current, target);
  const hi = Math.max(current, target);
  const span = hi - lo || Math.abs(target) * 0.5 || 1;
  const pad = span * 0.4;
  const displayMin = lo - pad;
  const displayMax = hi + pad;
  const displayRange = displayMax - displayMin || 1;

  const pct = (v: number) =>
    Math.max(0, Math.min(100, ((v - displayMin) / displayRange) * 100));
  const valuePct = pct(current);
  const targetPct = pct(target);
  const goodLeft = lowerIsBetter(comparator);

  const dotColor =
    status === "good" ? "bg-good" : status === "warn" ? "bg-warn" : "bg-alert";

  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ""}`}>
      <span className="relative h-1.5 w-24 rounded-full bg-canvas-sunken">
        {/* satisfied-side shading */}
        <span
          className="absolute h-full rounded-full bg-good/25"
          style={
            goodLeft
              ? { left: 0, width: `${targetPct}%` }
              : { left: `${targetPct}%`, right: 0 }
          }
          aria-hidden
        />
        {/* target threshold line */}
        <span
          className="absolute top-1/2 h-3 w-px -translate-y-1/2 bg-tertiary"
          style={{ left: `${targetPct}%` }}
          aria-hidden
        />
        {/* current value dot */}
        <span
          className={`absolute top-1/2 h-2.5 w-1 -translate-y-1/2 rounded-sm ${dotColor}`}
          style={{ left: `${valuePct}%` }}
          aria-hidden
        />
      </span>
      <span className="font-mono text-[10px] text-tertiary">
        {formatNum(current)}
        {unit ? unit : ""} → {comparatorLabel(comparator, target, unit)}
      </span>
    </span>
  );
}

function comparatorLabel(cmp: Comparator, target: number, unit?: string): string {
  return `${COMPARATOR_SYMBOL[cmp]} ${formatNum(target)}${unit ?? ""}`;
}

function formatNum(n: number): string {
  return formatNumber(n, 1);
}
