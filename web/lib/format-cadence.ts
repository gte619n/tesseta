// Turns "one every N days" (avgIntervalDays = 60 / observations) into a compact,
// human label for the biometrics rows: "≈ daily", "≈ every 7.5d", or a weekly
// rate when readings come more than once a day. null → no readings in the window.
export function formatCadence(avgIntervalDays: number | null): string {
  if (avgIntervalDays == null) return "no recent data";
  if (avgIntervalDays >= 0.9 && avgIntervalDays <= 1.15) return "≈ daily";
  if (avgIntervalDays >= 1) return `≈ every ${avgIntervalDays.toFixed(1)}d`;
  const perWeek = 7 / avgIntervalDays;
  return `≈ ${perWeek.toFixed(0)}×/wk`;
}
