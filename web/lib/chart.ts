// Pure helpers for computing chart paths from a series. Mirrors the inline
// IIFE in the dashboard mockup so the rendered SVG is identical.

export type ChartGeometry = {
  width: number;
  height: number;
  yMin: number;
  yMax: number;
  padX: number;
  padBottom: number;
};

export function projectSeries(
  series: number[],
  geom: ChartGeometry,
): { x: number; y: number }[] {
  const { width, height, yMin, yMax, padX, padBottom } = geom;
  const xRange = width - padX - 12;
  const yRange = height - padBottom - 30;
  const lastIdx = series.length - 1 || 1;
  return series.map((v, i) => ({
    x: padX + (i / lastIdx) * xRange,
    y: padBottom + ((yMax - v) / (yMax - yMin)) * yRange,
  }));
}

export function toLinePath(points: { x: number; y: number }[]): string {
  if (points.length === 0) return "";
  const [first, ...rest] = points;
  if (!first) return "";
  let d = `M ${first.x.toFixed(1)} ${first.y.toFixed(1)}`;
  for (const p of rest) d += ` L ${p.x.toFixed(1)} ${p.y.toFixed(1)}`;
  return d;
}

export function toAreaPath(
  points: { x: number; y: number }[],
  baselineY: number,
): string {
  if (points.length === 0) return "";
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return "";
  const line = toLinePath(points);
  return `${line} L ${last.x.toFixed(1)} ${baselineY.toFixed(1)} L ${first.x.toFixed(1)} ${baselineY.toFixed(1)} Z`;
}

export function movingAverage(series: number[], window: number): number[] {
  return series.map((_, i) => {
    const start = Math.max(0, i - (window - 1));
    const slice = series.slice(start, i + 1);
    const sum = slice.reduce((a, b) => a + b, 0);
    return sum / slice.length;
  });
}

// ── Axis + bar helpers (IMPL-WEB-WORKOUT-01 §6) ──────────────────────────
//
// Grown from the sparkline path helpers above to support the Overview's
// axis'd charts (weekly volume bars, strength trend). Pure so the geometry is
// unit-testable without rendering.

/**
 * "Nice" axis ticks from 0 (or `min`) up to at least `max`, using 1/2/5×10ⁿ
 * steps so labels read cleanly. Returns ascending tick values; the last is
 * ≥ `max`. A flat/zero series returns `[min]` (or `[0]`).
 */
export function niceTicks(min: number, max: number, count = 4): number[] {
  if (!isFinite(min) || !isFinite(max) || max <= min) return [min || 0];
  const span = max - min;
  const rawStep = span / Math.max(1, count);
  const magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
  const normalized = rawStep / magnitude;
  const niceStep =
    magnitude * (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10);
  const start = Math.floor(min / niceStep) * niceStep;
  const end = Math.ceil(max / niceStep) * niceStep;
  const ticks: number[] = [];
  // Guard against fp drift accumulating over the loop.
  for (let v = start, i = 0; v <= end + niceStep * 1e-9 && i < 100; v += niceStep, i++) {
    ticks.push(Number(v.toFixed(6)));
  }
  return ticks;
}

export type Bar = { x: number; y: number; width: number; height: number; index: number };

export type BarLayout = {
  bars: Bar[];
  baselineY: number;
  yMax: number;
};

/**
 * Evenly-spaced vertical bars across the plot area, sharing a common yMax so
 * heights are comparable. `yMax` defaults to the series max (bumped to the next
 * nice tick); zero-value bars get zero height. Bars sit on `baselineY`.
 */
export function barLayout(
  values: number[],
  geom: {
    width: number;
    height: number;
    padX: number;
    padTop: number;
    padBottom: number;
    gap?: number;
  },
  yMaxOverride?: number,
): BarLayout {
  const { width, height, padX, padTop, padBottom } = geom;
  const gap = geom.gap ?? 2;
  const plotW = Math.max(0, width - padX * 2);
  const plotH = Math.max(0, height - padTop - padBottom);
  const baselineY = padTop + plotH;
  const n = values.length || 1;
  const slot = plotW / n;
  const barWidth = Math.max(1, slot - gap);
  const rawMax = yMaxOverride ?? Math.max(0, ...values);
  const ticks = niceTicks(0, rawMax, 4);
  const yMax = ticks[ticks.length - 1] || 1;
  const bars: Bar[] = values.map((v, i) => {
    const h = yMax > 0 ? (Math.max(0, v) / yMax) * plotH : 0;
    return {
      x: padX + i * slot + gap / 2,
      y: baselineY - h,
      width: barWidth,
      height: h,
      index: i,
    };
  });
  return { bars, baselineY, yMax };
}
