"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import { weightValue } from "@/lib/units";
import {
  movingAverage,
  projectSeries,
  toAreaPath,
  toLinePath,
} from "@/lib/chart";

type Props = {
  variant?: "desktop" | "foldable";
  // Weight series in lb, oldest → newest.
  series: number[];
  // Y-axis visible range in lb. Caller computes from data with padding.
  yMin: number;
  yMax: number;
  // X-axis tick labels at fixed pixel positions in the 600-wide viewBox.
  xLabels: { x: number; label: string }[];
};

export function WeightChart({
  variant = "desktop",
  series: seriesLb,
  yMin: yMinLb,
  yMax: yMaxLb,
  xLabels,
}: Props) {
  // Convert the canonical lb series + axis bounds to the chosen weight
  // unit. Defaults to lb so the first client render matches the server.
  const { prefs } = useUnits();
  const series = seriesLb.map((v) => weightValue(v, prefs.weight));
  const yMin = weightValue(yMinLb, prefs.weight);
  const yMax = weightValue(yMaxLb, prefs.weight);

  const isFoldable = variant === "foldable";
  const geom = isFoldable
    ? { width: 600, height: 140, yMin, yMax, padX: 26, padBottom: 14 }
    : { width: 600, height: 160, yMin, yMax, padX: 24, padBottom: 20 };

  const projected = projectSeries(series, geom);
  const ma = projectSeries(movingAverage(series, 7), geom);
  const baselineY = geom.height - (isFoldable ? 22 : 20);
  const linePath = toLinePath(projected);
  const areaPath = toAreaPath(projected, baselineY);
  const maPath = toLinePath(ma);
  const current = projected[projected.length - 1];

  const gridLines = isFoldable ? [26, 62, 98] : [30, 70, 110];
  const yAxisYs = isFoldable ? [24, 60, 96, 128] : [28, 68, 108, 146];
  const yAxisLabels = yAxisLabelsFor(yMin, yMax);
  const xLabelY = isFoldable ? 138 : 155;
  const fontSize = isFoldable ? 8 : 9;

  return (
    <svg
      viewBox={`0 0 ${geom.width} ${geom.height}`}
      className="block h-auto w-full"
      aria-label="Weight trend"
    >
      {gridLines.map((y) => (
        <line
          key={y}
          x1={0}
          y1={y}
          x2={geom.width}
          y2={y}
          stroke="var(--color-border-subtle)"
          strokeWidth="0.5"
          strokeDasharray="2 3"
        />
      ))}
      {yAxisYs.map((y, i) => (
        <text
          key={y}
          x={4}
          y={y}
          fontSize={fontSize}
          fill="var(--color-quaternary)"
          fontFamily="var(--font-mono)"
        >
          {yAxisLabels[i]}
        </text>
      ))}
      <path d={areaPath} fill="var(--color-accent)" fillOpacity="0.06" stroke="none" />
      <path
        d={linePath}
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth="1.5"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      <path
        d={maPath}
        fill="none"
        stroke="var(--color-primary)"
        strokeWidth="1"
        strokeDasharray="3 3"
        strokeLinejoin="round"
        opacity="0.4"
      />
      {current ? (
        <circle
          cx={current.x}
          cy={current.y}
          r="3.5"
          fill="var(--color-accent)"
          stroke="#FFFFFF"
          strokeWidth="2"
        />
      ) : null}
      {xLabels.map((x) => (
        <text
          key={x.label + x.x}
          x={x.x}
          y={xLabelY}
          fontSize={fontSize}
          fill="var(--color-quaternary)"
          fontFamily="var(--font-mono)"
        >
          {x.label}
        </text>
      ))}
    </svg>
  );
}

// Four evenly-spaced y-axis labels (top → bottom).
function yAxisLabelsFor(yMin: number, yMax: number): string[] {
  const top = yMax;
  const step = (yMax - yMin) / 3;
  return [0, 1, 2, 3].map((i) => (top - i * step).toFixed(0));
}
