"use client";

import { useState } from "react";
import type { Macros } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";

type Props = {
  current: Macros | null;
  setTarget: (macros: Macros) => Promise<void>;
};

const MACRO_FIELDS: {
  key: keyof Macros;
  label: string;
  unit: string;
  placeholder: string;
}[] = [
  { key: "caloriesKcal", label: "Calories", unit: "kcal", placeholder: "e.g. 2500" },
  { key: "proteinGrams", label: "Protein", unit: "g", placeholder: "e.g. 180" },
  { key: "carbsGrams", label: "Carbs", unit: "g", placeholder: "e.g. 250" },
  { key: "fatGrams", label: "Fat", unit: "g", placeholder: "e.g. 80" },
  { key: "fiberGrams", label: "Fiber", unit: "g", placeholder: "e.g. 30" },
  { key: "sugarGrams", label: "Sugar", unit: "g", placeholder: "e.g. 40" },
];

function macrosToStrings(m: Macros | null): Record<keyof Macros, string> {
  return {
    caloriesKcal: m?.caloriesKcal != null ? String(m.caloriesKcal) : "",
    proteinGrams: m?.proteinGrams != null ? String(m.proteinGrams) : "",
    carbsGrams: m?.carbsGrams != null ? String(m.carbsGrams) : "",
    fatGrams: m?.fatGrams != null ? String(m.fatGrams) : "",
    fiberGrams: m?.fiberGrams != null ? String(m.fiberGrams) : "",
    sugarGrams: m?.sugarGrams != null ? String(m.sugarGrams) : "",
  };
}

export function TargetForm({ current, setTarget }: Props) {
  const toast = useToast();
  const [fields, setFields] = useState<Record<keyof Macros, string>>(
    macrosToStrings(current),
  );
  const [saving, setSaving] = useState(false);

  // "Calculate from calories" helper
  const [calorieGoal, setCalorieGoal] = useState("");

  function numOr(s: string): number | null {
    const n = parseFloat(s);
    return isNaN(n) ? null : n;
  }

  function handleFieldChange(key: keyof Macros, value: string) {
    setFields((prev) => ({ ...prev, [key]: value }));
  }

  function handleCalculate() {
    const cal = parseFloat(calorieGoal);
    if (isNaN(cal) || cal <= 0) {
      toast.error("Enter a valid calorie goal");
      return;
    }
    // Standard macro split: 30% protein, 40% carbs, 30% fat
    const proteinG = Math.round((cal * 0.3) / 4);
    const carbsG = Math.round((cal * 0.4) / 4);
    const fatG = Math.round((cal * 0.3) / 9);
    setFields((prev) => ({
      ...prev,
      caloriesKcal: String(cal),
      proteinGrams: String(proteinG),
      carbsGrams: String(carbsG),
      fatGrams: String(fatG),
    }));
    toast.info("Macros calculated — adjust to taste.");
  }

  async function handleSave() {
    const macros: Macros = {
      caloriesKcal: numOr(fields.caloriesKcal),
      proteinGrams: numOr(fields.proteinGrams),
      carbsGrams: numOr(fields.carbsGrams),
      fatGrams: numOr(fields.fatGrams),
      fiberGrams: numOr(fields.fiberGrams),
      sugarGrams: numOr(fields.sugarGrams),
    };
    setSaving(true);
    try {
      await setTarget(macros);
      toast.success("Daily targets saved");
    } catch {
      toast.error("Failed to save targets");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* Calorie calculator helper */}
      <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface p-5">
        <h2 className="m-0 text-[14px] font-medium text-primary">
          Calculate from calories
        </h2>
        <p className="mt-1 text-[12px] text-secondary">
          Enter a calorie goal and we&apos;ll suggest a macro split (30% protein,
          40% carbs, 30% fat). Adjust as needed.
        </p>
        <div className="mt-3 flex gap-2">
          <input
            type="number"
            min="0"
            step="50"
            value={calorieGoal}
            onChange={(e) => setCalorieGoal(e.target.value)}
            placeholder="e.g. 2500 kcal"
            className="flex-1 rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          />
          <button
            type="button"
            onClick={handleCalculate}
            className="caps-mono rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
          >
            Calculate
          </button>
        </div>
      </div>

      {/* Target form */}
      <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface p-5">
        <h2 className="m-0 text-[14px] font-medium text-primary">
          Daily macro targets
        </h2>
        <p className="mt-1 text-[12px] text-secondary">
          Leave any field blank to skip tracking that nutrient.
        </p>

        <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3">
          {MACRO_FIELDS.map(({ key, label, unit, placeholder }) => (
            <div key={key}>
              <label className="mb-1 block text-[11px] font-medium text-secondary">
                {label} ({unit})
              </label>
              <input
                type="number"
                min="0"
                step="1"
                value={fields[key]}
                onChange={(e) => handleFieldChange(key, e.target.value)}
                placeholder={placeholder}
                className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              />
            </div>
          ))}
        </div>

        <div className="mt-5 flex justify-end">
          <button
            type="button"
            onClick={handleSave}
            disabled={saving}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? "Saving…" : "Save targets"}
          </button>
        </div>
      </div>
    </div>
  );
}
