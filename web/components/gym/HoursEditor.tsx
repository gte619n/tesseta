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

// Default hours applied when a closed day is toggled open.
const DEFAULT_SLOT: HoursSlot = { open: "09:00", close: "17:00" };

// 15-minute increments across the day. Value is 24h "HH:mm" (the stored
// format); label is friendly 12h, e.g. "9:00 AM".
const TIME_OPTIONS: { value: string; label: string }[] = (() => {
  const opts: { value: string; label: string }[] = [];
  for (let h = 0; h < 24; h++) {
    for (let m = 0; m < 60; m += 15) {
      const value = `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
      const period = h < 12 ? "AM" : "PM";
      const hour12 = h % 12 === 0 ? 12 : h % 12;
      const label = `${hour12}:${String(m).padStart(2, "0")} ${period}`;
      opts.push({ value, label });
    }
  }
  return opts;
})();

export function HoursEditor({ value, onChange, disabled }: Props) {
  const hours = value || {};

  function setSlot(day: DayOfWeek, slot: HoursSlot | null) {
    const updated = { ...hours };
    if (slot) {
      updated[day] = slot;
    } else {
      // Absent day = closed (matches the backend's Map<DayOfWeek, HoursSlot>).
      delete updated[day];
    }
    onChange(Object.keys(updated).length > 0 ? updated : null);
  }

  function toggleClosed(day: DayOfWeek) {
    if (hours[day]) {
      setSlot(day, null); // open -> closed
    } else {
      setSlot(day, { ...DEFAULT_SLOT }); // closed -> open
    }
  }

  function copyFromDay(targetDay: DayOfWeek, sourceDay: DayOfWeek) {
    const source = hours[sourceDay];
    if (!source) return;
    setSlot(targetDay, { ...source });
  }

  return (
    <div className="space-y-3">
      <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
        Hours by day
      </span>

      <div className="space-y-2">
        {DAYS.map((day, index) => {
          const slot = hours[day.key];
          const isClosed = !slot;
          const prevDay = index > 0 ? DAYS[index - 1] : null;
          const canCopy = !!prevDay && !!hours[prevDay.key] && !disabled;

          return (
            <div key={day.key} className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => toggleClosed(day.key)}
                disabled={disabled}
                title={isClosed ? `Mark ${day.label} open` : `Mark ${day.label} closed`}
                className="w-24 cursor-pointer text-left text-[12px] text-secondary hover:text-primary disabled:cursor-default disabled:opacity-50"
              >
                {day.label}
              </button>

              {prevDay ? (
                <button
                  type="button"
                  onClick={() => copyFromDay(day.key, prevDay.key)}
                  disabled={!canCopy}
                  title={`Copy hours from ${prevDay.label}`}
                  aria-label={`Copy hours from ${prevDay.label}`}
                  className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-1.5 py-1.5 text-[12px] leading-none text-secondary hover:bg-canvas-muted disabled:cursor-default disabled:opacity-40"
                >
                  ↑
                </button>
              ) : (
                <span className="w-[26px]" aria-hidden="true" />
              )}

              {isClosed ? (
                <button
                  type="button"
                  onClick={() => toggleClosed(day.key)}
                  disabled={disabled}
                  className="flex-1 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas-muted px-2.5 py-1.5 text-left text-[13px] text-tertiary hover:text-secondary disabled:cursor-default disabled:opacity-50"
                >
                  Closed
                </button>
              ) : (
                <div className="flex flex-1 items-center gap-2">
                  <TimeSelect
                    value={slot.open}
                    onChange={(v) => setSlot(day.key, { open: v, close: slot.close })}
                    disabled={disabled}
                    ariaLabel={`${day.label} opening time`}
                  />
                  <span className="text-tertiary">–</span>
                  <TimeSelect
                    value={slot.close}
                    onChange={(v) => setSlot(day.key, { open: slot.open, close: v })}
                    disabled={disabled}
                    ariaLabel={`${day.label} closing time`}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TimeSelect({
  value,
  onChange,
  disabled,
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  ariaLabel: string;
}) {
  // Preserve a legacy off-grid value (e.g. "09:05") so editing one field
  // never silently snaps the other to the 15-minute grid.
  const offGrid = value && !TIME_OPTIONS.some((o) => o.value === value);

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      aria-label={ariaLabel}
      className="flex-1 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 text-[13px] text-primary accent-accent focus:border-accent focus:outline-none disabled:cursor-default disabled:opacity-50"
    >
      {offGrid && <option value={value}>{value}</option>}
      {TIME_OPTIONS.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}
