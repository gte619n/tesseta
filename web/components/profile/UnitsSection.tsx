"use client";

import { useUnits } from "@/components/ui/UnitsProvider";
import type { HeightUnit, TemperatureUnit, WeightUnit } from "@/lib/units";

// Segmented toggles mirroring the Android Settings → Units section:
// Height (ft / in | cm), Weight (lb | kg), Temperature (°F | °C).
export function UnitsSection() {
  const {
    prefs,
    setHeightUnit,
    setWeightUnit,
    setTemperatureUnit,
  } = useUnits();

  return (
    <div className="mt-2 flex flex-col gap-3">
      <ToggleRow<HeightUnit>
        label="Height"
        options={[
          { value: "FT_IN", text: "ft / in" },
          { value: "CM", text: "cm" },
        ]}
        selected={prefs.height}
        onSelect={setHeightUnit}
      />
      <ToggleRow<WeightUnit>
        label="Weight"
        options={[
          { value: "LB", text: "lb" },
          { value: "KG", text: "kg" },
        ]}
        selected={prefs.weight}
        onSelect={setWeightUnit}
      />
      <ToggleRow<TemperatureUnit>
        label="Temperature"
        options={[
          { value: "F", text: "°F" },
          { value: "C", text: "°C" },
        ]}
        selected={prefs.temperature}
        onSelect={setTemperatureUnit}
      />
    </div>
  );
}

function ToggleRow<T extends string>({
  label,
  options,
  selected,
  onSelect,
}: {
  label: string;
  options: { value: T; text: string }[];
  selected: T;
  onSelect: (value: T) => void;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="font-mono text-[13px] text-primary">{label}</span>
      <div
        role="group"
        aria-label={label}
        className="flex gap-0.5 rounded-[9px] border-[0.5px] border-border-default p-0.5"
      >
        {options.map((opt) => {
          const isSelected = opt.value === selected;
          return (
            <button
              key={opt.value}
              type="button"
              aria-pressed={isSelected}
              onClick={() => onSelect(opt.value)}
              className={`caps-mono cursor-pointer rounded-[7px] px-3.5 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                isSelected
                  ? "bg-accent text-inverse"
                  : "text-secondary hover:text-primary"
              }`}
            >
              {opt.text}
            </button>
          );
        })}
      </div>
    </div>
  );
}
