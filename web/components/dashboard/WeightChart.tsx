import {
  movingAverage,
  projectSeries,
  toAreaPath,
  toLinePath,
} from "@/lib/chart";
import { bodyComp, weightSeries } from "@/lib/fixtures/dashboard";

type Props = {
  variant?: "desktop" | "foldable";
};

export function WeightChart({ variant = "desktop" }: Props) {
  const isFoldable = variant === "foldable";
  const geom = isFoldable
    ? { width: 600, height: 140, yMin: 188, yMax: 194, padX: 26, padBottom: 14 }
    : { width: 600, height: 160, yMin: 188, yMax: 194, padX: 24, padBottom: 20 };

  const projected = projectSeries(weightSeries, geom);
  const ma = projectSeries(movingAverage(weightSeries, 7), geom);
  const baselineY = geom.height - (isFoldable ? 22 : 20);
  const linePath = toLinePath(projected);
  const areaPath = toAreaPath(projected, baselineY);
  const maPath = toLinePath(ma);
  const current = projected[projected.length - 1];

  const gridLines = isFoldable ? [26, 62, 98] : [30, 70, 110];
  const yAxis = isFoldable
    ? [
        { y: 24, label: "194" },
        { y: 60, label: "192" },
        { y: 96, label: "190" },
        { y: 128, label: "188" },
      ]
    : [
        { y: 28, label: "194" },
        { y: 68, label: "192" },
        { y: 108, label: "190" },
        { y: 146, label: "188" },
      ];
  const xLabelY = isFoldable ? 138 : 155;
  const fontSize = isFoldable ? 8 : 9;

  return (
    <svg
      viewBox={`0 0 ${geom.width} ${geom.height}`}
      className="block h-auto w-full"
      aria-label="Weight trend over the past 90 days"
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
      {yAxis.map((a) => (
        <text
          key={a.label}
          x={4}
          y={a.y}
          fontSize={fontSize}
          fill="var(--color-quaternary)"
          fontFamily="var(--font-mono)"
        >
          {a.label}
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
      {bodyComp.axis.xLabels.map((x) => (
        <text
          key={x.label}
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
