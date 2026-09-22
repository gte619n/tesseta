// Per-hand → total load display (IMPL-PROG-LOAD-01 D3/D9/IL-11).
//
// The lifter logs dumbbell / dual-cable weight PER HAND (a pair of 60s is
// logged as 60). The backend sends a `loadFactor` (1 or 2) and pre-doubled
// TOTAL values. The dashboard shows the TOTAL as the primary metric — so
// dumbbell lifts compare to barbell lifts ("traditional metrics") — with the
// per-hand number as a secondary label.

import { formatNumber } from "@/lib/format-number";

// True when the exercise is logged per hand (a pair) and therefore doubles.
export function isPerHand(loadFactor: number | undefined | null): boolean {
  return (loadFactor ?? 1) === 2;
}

// The secondary "· 60/hand" suffix for a per-hand lift, or "" otherwise.
// `totalLbs` is the pre-doubled total; per-hand = total / factor.
export function perHandSuffix(
  totalLbs: number,
  loadFactor: number | undefined | null,
): string {
  if (!isPerHand(loadFactor)) return "";
  const perHand = totalLbs / 2;
  return ` · ${formatNumber(perHand)}/hand`;
}

// A full "120 lb total · 60/hand" label for a per-hand lift, or "135 lb" for a
// total-load lift. `unit` defaults to "lb" (the strength surfaces are lb-only).
export function perHandLabel(
  totalLbs: number,
  loadFactor: number | undefined | null,
  unit = "lb",
): string {
  if (!isPerHand(loadFactor)) return `${formatNumber(totalLbs)} ${unit}`;
  return `${formatNumber(totalLbs)} ${unit} total · ${formatNumber(totalLbs / 2)}/hand`;
}
