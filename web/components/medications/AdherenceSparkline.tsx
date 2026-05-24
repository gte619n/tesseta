"use client";

import type { DayAdherence } from "@/lib/types/medication";

interface AdherenceSparklineProps {
  data: DayAdherence[];
  percentage?: number;
}

/**
 * 30-day adherence sparkline showing taken/missed doses as tick marks.
 */
export function AdherenceSparkline({ data, percentage }: AdherenceSparklineProps) {
  // Fill to 30 days if needed
  const days = data.length > 0 ? data.slice(-30) : [];

  if (days.length === 0) {
    return (
      <div className="flex items-center gap-2">
        <span className="font-mono text-[10px] text-tertiary">No data</span>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <div className="flex gap-0.5">
        {days.map((day, i) => (
          <div
            key={day.date || i}
            className={`h-3 w-1 rounded-sm ${
              day.taken ? "bg-good" : "bg-border-subtle"
            }`}
            title={`${day.date}: ${day.taken ? "Taken" : "Missed"}`}
          />
        ))}
      </div>
      {percentage !== undefined && (
        <span className="font-mono text-[10px] text-tertiary">
          {Math.round(percentage)}%
        </span>
      )}
    </div>
  );
}
