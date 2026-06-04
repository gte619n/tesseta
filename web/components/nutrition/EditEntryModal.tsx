"use client";

import { useMemo, useState } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import type { Entry, Meal, Macros, UpdateEntryBody } from "@/lib/types/nutrition";
import { MEAL_LABELS, MEALS, QUANTITY_STEPS } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";

type Props = {
  isOpen: boolean;
  onClose: () => void;
  entry: Entry;
  date: string;
  updateEntry: (
    date: string,
    entryId: string,
    body: UpdateEntryBody,
  ) => Promise<void>;
};

const MACRO_FIELDS: { key: keyof Macros; label: string }[] = [
  { key: "caloriesKcal", label: "Calories (kcal)" },
  { key: "proteinGrams", label: "Protein (g)" },
  { key: "carbsGrams", label: "Carbs (g)" },
  { key: "fatGrams", label: "Fat (g)" },
  { key: "fiberGrams", label: "Fiber (g)" },
  { key: "sugarGrams", label: "Sugar (g)" },
];

function numOr(s: string): number | null {
  const n = parseFloat(s);
  return isNaN(n) ? null : n;
}

function macroToStr(v: number | null): string {
  return v === null ? "" : String(Math.round(v * 10) / 10);
}

// Derive the per-100g baseline implied by the entry's frozen snapshot, so
// changing serving grams / quantity re-scales macros the same way the add
// flow computes them. Falls back to the raw snapshot when grams×qty is 0.
function derivedPer100g(entry: Entry): Macros {
  const factor = (entry.servingGrams * entry.quantity) / 100;
  const back = (v: number | null) =>
    v === null ? null : factor > 0 ? v / factor : v;
  return {
    caloriesKcal: back(entry.macros.caloriesKcal),
    proteinGrams: back(entry.macros.proteinGrams),
    carbsGrams: back(entry.macros.carbsGrams),
    fatGrams: back(entry.macros.fatGrams),
    fiberGrams: back(entry.macros.fiberGrams),
    sugarGrams: back(entry.macros.sugarGrams),
  };
}

function scale(per100g: Macros, grams: number, quantity: number): Macros {
  const factor = (grams * quantity) / 100;
  const s = (v: number | null) => (v === null ? null : v * factor);
  return {
    caloriesKcal: s(per100g.caloriesKcal),
    proteinGrams: s(per100g.proteinGrams),
    carbsGrams: s(per100g.carbsGrams),
    fatGrams: s(per100g.fatGrams),
    fiberGrams: s(per100g.fiberGrams),
    sugarGrams: s(per100g.sugarGrams),
  };
}

export function EditEntryModal({
  isOpen,
  onClose,
  entry,
  date,
  updateEntry,
}: Props) {
  const toast = useToast();
  const per100g = useMemo(() => derivedPer100g(entry), [entry]);

  const [name, setName] = useState(entry.foodName);
  const [meal, setMeal] = useState<Meal>(entry.meal);
  const [servingLabel, setServingLabel] = useState(entry.servingLabel);
  const [servingGrams, setServingGrams] = useState(String(entry.servingGrams));
  const [quantity, setQuantity] = useState(entry.quantity);
  const [quantityStr, setQuantityStr] = useState(String(entry.quantity));
  const [macros, setMacros] = useState<Record<keyof Macros, string>>({
    caloriesKcal: macroToStr(entry.macros.caloriesKcal),
    proteinGrams: macroToStr(entry.macros.proteinGrams),
    carbsGrams: macroToStr(entry.macros.carbsGrams),
    fatGrams: macroToStr(entry.macros.fatGrams),
    fiberGrams: macroToStr(entry.macros.fiberGrams),
    sugarGrams: macroToStr(entry.macros.sugarGrams),
  });
  const [saving, setSaving] = useState(false);

  // Re-scale macros from the baseline when the portion changes (only meaningful
  // for catalog foods that carry a per-100g baseline).
  function recompute(nextGrams: number, nextQty: number) {
    const next = scale(per100g, nextGrams, nextQty);
    setMacros({
      caloriesKcal: macroToStr(next.caloriesKcal),
      proteinGrams: macroToStr(next.proteinGrams),
      carbsGrams: macroToStr(next.carbsGrams),
      fatGrams: macroToStr(next.fatGrams),
      fiberGrams: macroToStr(next.fiberGrams),
      sugarGrams: macroToStr(next.sugarGrams),
    });
  }

  function onGramsChange(v: string) {
    setServingGrams(v);
    const g = numOr(v);
    if (g !== null && g > 0) recompute(g, quantity);
  }

  function onQuantityChange(q: number) {
    setQuantity(q);
    setQuantityStr(String(q));
    const g = numOr(servingGrams);
    if (g !== null && g > 0) recompute(g, q);
  }

  // Free-form quantity entry (alongside the quick chips).
  function onQuantityText(v: string) {
    setQuantityStr(v);
    const q = numOr(v);
    if (q !== null && q > 0) {
      setQuantity(q);
      const g = numOr(servingGrams);
      if (g !== null && g > 0) recompute(g, q);
    }
  }

  function setMacroField(key: keyof Macros, v: string) {
    setMacros((prev) => ({ ...prev, [key]: v }));
  }

  if (!isOpen) return null;

  async function handleSave() {
    const grams = numOr(servingGrams);
    if (grams === null || grams <= 0) {
      toast.error("Serving size must be greater than 0");
      return;
    }
    const body: UpdateEntryBody = {
      meal,
      foodName: name.trim() || entry.foodName,
      servingLabel: servingLabel.trim() || entry.servingLabel,
      servingGrams: grams,
      quantity,
      macros: {
        caloriesKcal: numOr(macros.caloriesKcal),
        proteinGrams: numOr(macros.proteinGrams),
        carbsGrams: numOr(macros.carbsGrams),
        fatGrams: numOr(macros.fatGrams),
        fiberGrams: numOr(macros.fiberGrams),
        sugarGrams: numOr(macros.sugarGrams),
      },
    };
    setSaving(true);
    try {
      await updateEntry(date, entry.entryId, body);
      toast.success("Entry updated");
      onClose();
    } catch {
      toast.error("Failed to update entry");
    } finally {
      setSaving(false);
    }
  }

  // Live amount readout that tracks serving grams × quantity and calories.
  const effectiveGrams = Math.round((numOr(servingGrams) ?? 0) * quantity);
  const liveKcal = numOr(macros.caloriesKcal);
  const amountLabel = `${effectiveGrams} g${
    liveKcal !== null ? ` · ${Math.round(liveKcal)} kcal` : ""
  }`;

  return (
    <ModalBackdrop
      onClose={onClose}
      contentClassName="flex max-h-[90vh] w-[520px] max-w-[92vw] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
        {/* Header */}
        <div className="flex items-start justify-between gap-3 border-b-[0.5px] border-border-subtle px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <FoodImage
              imageUrl={entry.imageUrl}
              imageStatus={entry.imageStatus}
              size={64}
            />
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[16px] font-medium tracking-[-0.01em] text-primary">
                {name.trim() || entry.foodName}
              </h2>
              <div className="mt-1 font-mono text-[12px] tabular-nums text-secondary">
                {amountLabel}
              </div>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded-md p-1 text-tertiary hover:text-primary"
            aria-label="Close"
          >
            <i className="ti ti-x text-[16px]" aria-hidden />
          </button>
        </div>

        {/* Body */}
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-5">
          {/* Title */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Title
            </label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Entry name"
              className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          {/* Meal */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Meal
            </label>
            <div className="flex flex-wrap gap-1.5">
              {MEALS.map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => setMeal(m)}
                  className={`caps-mono rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                    meal === m
                      ? "border-accent bg-accent-bg text-accent-dim"
                      : "border-border-default bg-canvas text-secondary hover:border-border-strong"
                  }`}
                >
                  {MEAL_LABELS[m]}
                </button>
              ))}
            </div>
          </div>

          {/* Serving label + grams */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-[11px] font-medium text-secondary">
                Serving label
              </label>
              <input
                value={servingLabel}
                onChange={(e) => setServingLabel(e.target.value)}
                placeholder="e.g. 1 container, 1 slice, 100 g"
                className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-[11px] font-medium text-secondary">
                Serving size (g)
              </label>
              <input
                type="number"
                min="0"
                step="1"
                value={servingGrams}
                onChange={(e) => onGramsChange(e.target.value)}
                className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              />
            </div>
          </div>

          {/* Quantity: quick chips + free-form entry, with a live total */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Quantity
            </label>
            <div className="flex flex-wrap items-center gap-1.5">
              {QUANTITY_STEPS.map((q) => (
                <button
                  key={q}
                  type="button"
                  onClick={() => onQuantityChange(q)}
                  className={`caps-mono rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                    quantity === q
                      ? "border-accent bg-accent-bg text-accent-dim"
                      : "border-border-default bg-canvas text-secondary hover:border-border-strong"
                  }`}
                >
                  {q}×
                </button>
              ))}
              <input
                type="number"
                min="0"
                step="0.1"
                value={quantityStr}
                onChange={(e) => onQuantityText(e.target.value)}
                aria-label="Custom quantity"
                className="w-20 rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              />
            </div>
            <div className="mt-1.5 font-mono text-[11px] tabular-nums text-tertiary">
              = {amountLabel} total
            </div>
          </div>

          {/* Macros */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Macros
            </label>
            <div className="grid grid-cols-2 gap-3">
              {MACRO_FIELDS.map((f) => (
                <div key={f.key}>
                  <label className="mb-1 block text-[10px] text-tertiary">
                    {f.label}
                  </label>
                  <input
                    type="number"
                    min="0"
                    step="0.1"
                    value={macros[f.key]}
                    onChange={(e) => setMacroField(f.key, e.target.value)}
                    placeholder="0"
                    className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
                  />
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 border-t-[0.5px] border-border-subtle px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={saving}
            className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? "Saving…" : "Save changes"}
          </button>
        </div>
    </ModalBackdrop>
  );
}
