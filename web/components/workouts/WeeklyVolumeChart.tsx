"use client";

import { useState } from "react";
import type { WeekPoint } from "@/lib/types/workout-stats";
import { niceTicks } from "@/lib/chart";
import { formatWholeNumber } from "@/lib/format-number";
import { formatDateUpper } from "@/lib/format-date";

// Weekly training volume (IMPL-WEB-WORKOUT-01 D5): tonnage bars over the active
// weeks with a y-axis in pounds and a hover tooltip carrying exact tonnage +
// session count. Leading pre-program weeks (all zero) are trimmed so the bars
// fill the card width. Each bar is tinted by whether that week met the session
// target (IL-13).

const WIDTH = 560;
const HEIGHT = 200;
const PAD_LEFT = 46;
const PAD_RIGHT = 10;
const PAD_TOP = 12;
const PAD_BOTTOM = 28;

function tickLabel(v: number): string {
  if (v >= 1000) return `${Math.round(v / 100) / 10}k`;
  return `${Math.round(v)}`;
}

export function WeeklyVolumeChart({
  series,
  weeklyTarget,
}: {
  series: WeekPoint[];
  weeklyTarget: number;
}) {
  const [hover, setHover] = useState<number | null>(null);

  // Trim leading all-zero weeks so the bars fill the width (keep from the first
  // week with any activity onward).
  const firstActive = series.findIndex((w) => w.tonnageLbs > 0 || w.sessions > 0);
  const data = firstActive >= 0 ? series.slice(firstActive) : [];
  const hasData = data.length > 0;

  const maxTon = Math.max(0, ...data.map((w) => w.tonnageLbs));
  const ticks = niceTicks(0, maxTon, 3);
  const yMax = ticks[ticks.length - 1] || 1;

  const plotLeft = PAD_LEFT;
  const plotRight = WIDTH - PAD_RIGHT;
  const plotW = plotRight - plotLeft;
  const baselineY = HEIGHT - PAD_BOTTOM;
  const plotH = baselineY - PAD_TOP;
  const slot = plotW / Math.max(1, data.length);
  const gap = data.length > 20 ? 2 : 4;
  const barW = Math.max(2, slot - gap);

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Weekly volume
        </div>
        <div className="font-mono text-[11px] text-tertiary tabular-nums">
          peak {formatWholeNumber(maxTon)} lb
        </div>
      </div>

      {!hasData ? (
        <p className="mt-4 text-[13px] text-secondary">
          No volume yet. Completed sessions build this chart week by week.
        </p>
      ) : (
        <div className="relative mt-3">
          <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            className="w-full"
            role="img"
            aria-label="Weekly training tonnage in pounds"
          >
            {/* y-axis gridlines + labels (lb) */}
            {ticks.map((t) => {
              const y = baselineY - (t / yMax) * plotH;
              return (
                <g key={t}>
                  <line
                    x1={plotLeft}
                    y1={y}
                    x2={plotRight}
                    y2={y}
                    className={t === 0 ? "stroke-border-default" : "stroke-border-subtle"}
                    strokeWidth={t === 0 ? 1 : 0.5}
                  />
                  <text
                    x={plotLeft - 6}
                    y={y + 3}
                    textAnchor="end"
                    className="fill-tertiary font-mono text-[9px] tabular-nums"
                  >
                    {tickLabel(t)}
                  </text>
                </g>
              );
            })}

            {/* bars */}
            {data.map((week, i) => {
              const h = yMax > 0 ? (week.tonnageLbs / yMax) * plotH : 0;
              const x = plotLeft + i * slot + (slot - barW) / 2;
              const met = weeklyTarget > 0 && week.sessions >= weeklyTarget;
              return (
                <rect
                  key={week.weekStart}
                  x={x}
                  y={baselineY - h}
                  width={barW}
                  height={h}
                  rx={1}
                  data-testid="volume-bar"
                  data-week={week.weekStart}
                  className={
                    (met ? "fill-accent" : "fill-accent/35") +
                    (hover === i ? " opacity-100" : " opacity-90")
                  }
                  onMouseEnter={() => setHover(i)}
                  onMouseLeave={() => setHover((prev) => (prev === i ? null : prev))}
                />
              );
            })}

            {/* x-axis: first + last week */}
            <text
              x={plotLeft}
              y={HEIGHT - 8}
              textAnchor="start"
              className="fill-tertiary font-mono text-[9px]"
            >
              {formatDateUpper(data[0]!.weekStart).replace(/, \d{4}$/, "")}
            </text>
            <text
              x={plotRight}
              y={HEIGHT - 8}
              textAnchor="end"
              className="fill-tertiary font-mono text-[9px]"
            >
              {formatDateUpper(data[data.length - 1]!.weekStart).replace(/, \d{4}$/, "")}
            </text>
          </svg>

          {hover != null && data[hover] && (
            <div
              className="pointer-events-none absolute left-1/2 top-0 -translate-x-1/2 rounded-[8px] border border-border-default bg-canvas px-3 py-2 text-[11px] shadow-lg"
              data-testid="volume-tooltip"
            >
              <div className="font-mono tabular-nums text-tertiary">
                {formatDateUpper(data[hover]!.weekStart)}
              </div>
              <div className="mt-0.5 font-medium text-primary tabular-nums">
                {formatWholeNumber(data[hover]!.tonnageLbs)} lb
              </div>
              <div className="text-secondary tabular-nums">
                {data[hover]!.sessions} session
                {data[hover]!.sessions !== 1 && "s"}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
