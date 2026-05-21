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
