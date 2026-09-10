"use client";

import { useState } from "react";
import type { DrinkProposal, Drink, SaveDrinkBody } from "@/lib/types/nutrition";
import {
  alcoholGrams,
  standardDrinks,
  drinkCaloriesKcal,
} from "@/lib/types/nutrition";
import { formatNumber, formatWholeNumber } from "@/lib/format-number";

// A drink's mixer macros live at the per-serving level in the review form; the
// backend takes the same per-serving `macros` and folds in alcohol calories.
type FormState = {
  name: string;
  abv: string;
  volume: string;
  servingLabel: string;
  carbs: string;
  sugar: string;
  protein: string;
  fat: string;
};

function num(s: string): number | null {
  if (s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

function fromProposal(p: DrinkProposal): FormState {
  const m = p.servingMacros;
  return {
    name: p.name ?? "",
    abv: p.abvPercent != null ? String(p.abvPercent) : "",
    volume: p.servingVolumeMl != null ? String(p.servingVolumeMl) : "",
    servingLabel: "",
    carbs: m?.carbsGrams != null ? String(m.carbsGrams) : "",
    sugar: m?.sugarGrams != null ? String(m.sugarGrams) : "",
    protein: m?.proteinGrams != null ? String(m.proteinGrams) : "",
    fat: m?.fatGrams != null ? String(m.fatGrams) : "",
  };
}

function fromDrink(d: Drink): FormState {
  const a = d.alcohol;
  const serving = d.servingSizes[d.defaultServingIndex] ?? d.servingSizes[0];
  // Read the per-serving mixer macros the backend echoes directly (IL-13), so
  // the form shows exactly what was entered — no client-side re-derivation from
  // `macrosPer100g`, which drifts on float round-trips. `servingMacros` mixer
  // components are already per-serving and rounded 1 dp.
  const m = d.servingMacros;
  const str = (v: number | null | undefined): string =>
    v != null ? String(v) : "";
  return {
    name: d.name ?? "",
    abv: a?.abvPercent != null ? String(a.abvPercent) : "",
    volume: a?.servingVolumeMl != null ? String(a.servingVolumeMl) : "",
    servingLabel: serving?.label ?? "",
    carbs: str(m?.carbsGrams),
    sugar: str(m?.sugarGrams),
    protein: str(m?.proteinGrams),
    fat: str(m?.fatGrams),
  };
}

/**
 * The drink review / edit form (D11). Pre-filled from an AI proposal (add) or an
 * existing drink (edit); every field is editable. Shows the derived alcohol
 * readouts live (grams, standard drinks, total calories) and requires ABV +
 * volume to save (D17, alcohol-focused). Save calls back with a SaveDrinkBody.
 */
export function DrinkForm({
  proposal,
  drink,
  submitLabel,
  onSave,
  onCancel,
}: {
  proposal?: DrinkProposal;
  drink?: Drink;
  submitLabel: string;
  onSave: (body: SaveDrinkBody) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<FormState>(() =>
    drink ? fromDrink(drink) : proposal ? fromProposal(proposal) : {
      name: "",
      abv: "",
      volume: "",
      servingLabel: "",
      carbs: "",
      sugar: "",
      protein: "",
      fat: "",
    },
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const abv = num(form.abv);
  const volume = num(form.volume);
  const carbs = num(form.carbs);
  const sugar = num(form.sugar);
  const protein = num(form.protein);
  const fat = num(form.fat);

  const grams = alcoholGrams(abv, volume);
  const std = standardDrinks(abv, volume);
  const kcal = drinkCaloriesKcal(protein, carbs, fat, abv, volume);

  // D17: a drink is alcohol-focused — ABV and volume are required (and must be
  // positive) before it can be saved.
  const canSave =
    form.name.trim() !== "" &&
    abv != null &&
    abv > 0 &&
    volume != null &&
    volume > 0;

  function set<K extends keyof FormState>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSave() {
    if (!canSave || abv == null || volume == null) return;
    setError(null);
    setSaving(true);
    try {
      await onSave({
        ...(drink ? { id: drink.foodId } : {}),
        name: form.name.trim(),
        abvPercent: abv,
        servingVolumeMl: volume,
        servingLabel: form.servingLabel.trim() || null,
        macros: {
          caloriesKcal: null,
          proteinGrams: protein,
          carbsGrams: carbs,
          fatGrams: fat,
          fiberGrams: null,
          sugarGrams: sugar,
        },
      });
    } catch {
      setError("Couldn't save the drink. Please try again.");
      setSaving(false);
    }
  }

  return (
    <div className="space-y-4">
      <Field label="Name">
        <input
          type="text"
          value={form.name}
          onChange={(e) => set("name", e.target.value)}
          placeholder="Negroni"
          className={inputClass}
        />
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="ABV %" hint="required">
          <input
            type="number"
            inputMode="decimal"
            step="0.1"
            min="0"
            value={form.abv}
            onChange={(e) => set("abv", e.target.value)}
            placeholder="12"
            className={inputClass}
          />
        </Field>
        <Field label="Serving volume (ml)" hint="required">
          <input
            type="number"
            inputMode="decimal"
            step="1"
            min="0"
            value={form.volume}
            onChange={(e) => set("volume", e.target.value)}
            placeholder="90"
            className={inputClass}
          />
        </Field>
      </div>

      <Field label="Serving label" hint="optional">
        <input
          type="text"
          value={form.servingLabel}
          onChange={(e) => set("servingLabel", e.target.value)}
          placeholder="1 coupe"
          className={inputClass}
        />
      </Field>

      <div>
        <p className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Mixer macros (per serving, excluding alcohol)
        </p>
        <div className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Field label="Carbs (g)">
            <input
              type="number"
              inputMode="decimal"
              step="0.1"
              min="0"
              value={form.carbs}
              onChange={(e) => set("carbs", e.target.value)}
              placeholder="0"
              className={inputClass}
            />
          </Field>
          <Field label="Sugar (g)">
            <input
              type="number"
              inputMode="decimal"
              step="0.1"
              min="0"
              value={form.sugar}
              onChange={(e) => set("sugar", e.target.value)}
              placeholder="0"
              className={inputClass}
            />
          </Field>
          <Field label="Protein (g)">
            <input
              type="number"
              inputMode="decimal"
              step="0.1"
              min="0"
              value={form.protein}
              onChange={(e) => set("protein", e.target.value)}
              placeholder="0"
              className={inputClass}
            />
          </Field>
          <Field label="Fat (g)">
            <input
              type="number"
              inputMode="decimal"
              step="0.1"
              min="0"
              value={form.fat}
              onChange={(e) => set("fat", e.target.value)}
              placeholder="0"
              className={inputClass}
            />
          </Field>
        </div>
      </div>

      {/* Derived readouts (backend re-derives on save; numbers only, D19). */}
      <div className="grid grid-cols-3 gap-3 rounded-[10px] border-[0.5px] border-border-subtle bg-canvas-sunken/40 px-4 py-3">
        <Readout
          label="Alcohol"
          value={grams != null ? `${formatNumber(grams, 1)} g` : "—"}
        />
        <Readout
          label="Std drinks"
          value={std != null ? formatNumber(std, 1) : "—"}
        />
        <Readout
          label="Calories"
          value={kcal != null ? `${formatWholeNumber(kcal)} kcal` : "—"}
        />
      </div>

      {error && <p className="text-[12px] text-alert">{error}</p>}

      <div className="flex items-center justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary disabled:opacity-50"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={handleSave}
          disabled={!canSave || saving}
          className="cursor-pointer rounded-md bg-accent px-4 py-1.5 text-[12px] font-medium text-inverse disabled:opacity-50"
        >
          {saving ? "Saving…" : submitLabel}
        </button>
      </div>
    </div>
  );
}

const inputClass =
  "w-full rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1.5 text-[13px] text-primary tabular-nums outline-none focus:border-accent";

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 flex items-baseline gap-1.5">
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          {label}
        </span>
        {hint && (
          <span className="font-mono text-[9px] text-quaternary">{hint}</span>
        )}
      </span>
      {children}
    </label>
  );
}

function Readout({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
        {label}
      </div>
      <div className="mt-0.5 font-mono text-[14px] font-medium tabular-nums text-primary">
        {value}
      </div>
    </div>
  );
}
