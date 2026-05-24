"use client";

import type { DayOfWeek, HoursSlot } from "@/lib/types/gym";

type Props = {
  value: Partial<Record<DayOfWeek, HoursSlot>> | null;
  onChange: (hours: Partial<Record<DayOfWeek, HoursSlot>> | null) => void;
  disabled: boolean;
};

const DAYS: { key: DayOfWeek; label: string }[] = [
  { key: "mon", label: "Monday" },
  { key: "tue", label: "Tuesday" },
  { key: "wed", label: "Wednesday" },
  { key: "thu", label: "Thursday" },
  { key: "fri", label: "Friday" },
  { key: "sat", label: "Saturday" },
  { key: "sun", label: "Sunday" },
];

export function HoursEditor({ value, onChange, disabled }: Props) {
  const hours = value || {};

  function updateDay(day: DayOfWeek, open: string, close: string) {
    const updated = { ...hours };
    if (open && close) {
      updated[day] = { open, close };
    } else {
      delete updated[day];
    }
    onChange(Object.keys(updated).length > 0 ? updated : null);
  }

  function copyToWeekdays() {
    if (!hours.mon) return;
    const updated = { ...hours };
    const template = hours.mon;
    (["tue", "wed", "thu", "fri"] as DayOfWeek[]).forEach((day) => {
      updated[day] = { ...template };
    });
    onChange(updated);
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          Hours by day
        </span>
        {hours.mon && !disabled && (
          <button
            type="button"
            onClick={copyToWeekdays}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1 text-[11px] text-secondary hover:bg-canvas-muted"
          >
            Copy Mon to weekdays
          </button>
        )}
      </div>

      <div className="space-y-2">
        {DAYS.map((day) => {
          const slot = hours[day.key];
          return (
            <div key={day.key} className="flex items-center gap-3">
              <div className="w-24 text-[12px] text-secondary">{day.label}</div>
              <input
                type="time"
                value={slot?.open || ""}
                onChange={(e) =>
                  updateDay(day.key, e.target.value, slot?.close || "")
                }
                disabled={disabled}
                placeholder="Open"
                className="flex-1 rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1.5 text-[13px] text-primary disabled:opacity-50"
              />
              <span className="text-tertiary">–</span>
              <input
                type="time"
                value={slot?.close || ""}
                onChange={(e) =>
                  updateDay(day.key, slot?.open || "", e.target.value)
                }
                disabled={disabled}
                placeholder="Close"
                className="flex-1 rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1.5 text-[13px] text-primary disabled:opacity-50"
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}
