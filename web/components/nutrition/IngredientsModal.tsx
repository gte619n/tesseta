"use client";

import { useState } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import {
  QUANTITY_STEPS,
  type Entry,
  type EntryIngredient,
  type Macros,
  type UpdateEntryBody,
  type UpdateIngredientBody,
} from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";

type Props = {
  isOpen: boolean;
  onClose: () => void;
  entry: Entry;
  date: string;
  updateEntry: (date: string, entryId: string, body: UpdateEntryBody) => Promise<void>;
  updateIngredient: (
    date: string,
    entryId: string,
    index: number,
    body: UpdateIngredientBody,
  ) => Promise<void>;
};

function num(n: number | null | undefined): number {
  return n ?? 0;
}

function scaleKcal(per100g: Macros | null, grams: number, qty: number): number {
  if (!per100g || per100g.caloriesKcal == null) return 0;
  return Math.round((per100g.caloriesKcal * grams * qty) / 100);
}

/**
 * Modal for a composite (photo-logged) meal: the finished-meal image, an
 * editable meal title, and each ingredient with its raw-ingredient image and a
 * quantity multiplier (the portion size itself is fixed). A "portion of meal"
 * multiplier (the entry's own quantity) scales the whole meal's macros at once —
 * pick ½ when you split a meal. It's stored per entry, so the same meal logged
 * twice can have different portions. One Save commits the title, the portion and
 * any ingredient quantity changes together.
 */
export function IngredientsModal({
  isOpen,
  onClose,
  entry,
  date,
  updateEntry,
  updateIngredient,
}: Props) {
  const toast = useToast();
  const ingredients = entry.ingredients ?? [];
  const [title, setTitle] = useState(entry.foodName);
  const [qtys, setQtys] = useState<string[]>(
    ingredients.map((i) => String(i.quantity ?? 1)),
  );
  // Whole-meal portion = the entry's own quantity. Persisted per entry, so the
  // same meal logged twice can carry different portions. Scales the displayed
  // total; the ingredients keep their full-recipe amounts.
  const [portion, setPortion] = useState(String(entry.quantity ?? 1));
  const [saving, setSaving] = useState(false);

  const portionFactor = parseFloat(portion) || 1;

  // Full-recipe ingredient total, scaled once by the whole-meal portion.
  const liveKcal = Math.round(
    portionFactor *
      ingredients.reduce(
        (sum, ing, i) =>
          sum +
          scaleKcal(ing.macrosPer100g, num(ing.servingGrams), parseFloat(qtys[i] ?? "") || 1),
        0,
      ),
  );

  async function handleSave() {
    setSaving(true);
    try {
      // Ingredient quantity changes first — each resum preserves the existing
      // portion — then the entry update applies the (possibly new) portion to
      // the fresh ingredient totals.
      for (let i = 0; i < ingredients.length; i++) {
        const newQ = parseFloat(qtys[i] ?? "") || 1;
        const oldQ = ingredients[i]?.quantity ?? 1;
        if (newQ > 0 && newQ !== oldQ) {
          await updateIngredient(date, entry.entryId, i, { quantity: newQ });
        }
      }
      const body: UpdateEntryBody = {};
      const nextTitle = title.trim();
      if (nextTitle && nextTitle !== entry.foodName) body.foodName = nextTitle;
      if (portionFactor > 0 && portionFactor !== (entry.quantity ?? 1)) {
        body.quantity = portionFactor;
      }
      if (Object.keys(body).length > 0) {
        await updateEntry(date, entry.entryId, body);
      }
      toast.success("Meal updated");
      onClose();
    } catch {
      toast.error("Failed to save meal");
    } finally {
      setSaving(false);
    }
  }

  if (!isOpen) return null;

  return (
    <ModalBackdrop
      onClose={onClose}
      contentClassName="flex max-h-[90vh] w-[560px] max-w-[92vw] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
        {/* Header: finished-meal image + total */}
        <div className="flex items-start justify-between gap-3 border-b-[0.5px] border-border-subtle px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <FoodImage imageUrl={entry.imageUrl} imageStatus={entry.imageStatus} size={64} />
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[16px] font-medium tracking-[-0.01em] text-primary">
                {title.trim() || entry.foodName}
              </h2>
              <div className="mt-1 font-mono text-[12px] tabular-nums text-secondary">
                {liveKcal} kcal · {ingredients.length} ingredients
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
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-5">
          {/* Editable meal title */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Meal title
            </label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Meal name"
              className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          {/* Portion of the whole meal — scales every ingredient at once */}
          <div>
            <label className="mb-1.5 block text-[11px] font-medium text-secondary">
              Portion of meal
            </label>
            <div className="flex flex-wrap items-center gap-1.5">
              {QUANTITY_STEPS.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setPortion(String(p))}
                  className={`caps-mono rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                    portionFactor === p
                      ? "border-accent bg-accent-bg text-accent-dim"
                      : "border-border-default bg-canvas text-secondary hover:border-border-strong"
                  }`}
                >
                  {p}×
                </button>
              ))}
              <input
                type="number"
                min="0"
                step="0.1"
                value={portion}
                onChange={(e) => setPortion(e.target.value)}
                aria-label="Portion of meal"
                className="w-20 rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              />
            </div>
            {portionFactor !== 1 && (
              <div className="mt-1.5 font-mono text-[11px] tabular-nums text-tertiary">
                Counts as {portionFactor}× of the full meal
              </div>
            )}
          </div>

          {ingredients.map((ing, index) => (
            <IngredientRow
              key={`${ing.foodId ?? ing.name}-${index}`}
              ingredient={ing}
              quantity={qtys[index] ?? "1"}
              onQuantityChange={(v) =>
                setQtys((prev) => prev.map((q, i) => (i === index ? v : q)))
              }
            />
          ))}
        </div>

        {/* Footer: single save for the whole meal */}
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
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
    </ModalBackdrop>
  );
}

function IngredientRow({
  ingredient,
  quantity,
  onQuantityChange,
}: {
  ingredient: EntryIngredient;
  quantity: string;
  onQuantityChange: (v: string) => void;
}) {
  const baseGrams = num(ingredient.servingGrams);
  const q = parseFloat(quantity) || 1;
  const kcal = scaleKcal(ingredient.macrosPer100g, baseGrams, q);

  return (
    <div className="flex items-center gap-3 rounded-[10px] border-[0.5px] border-border-default bg-canvas p-3">
      <FoodImage imageUrl={ingredient.imageUrl} imageStatus={ingredient.imageStatus} size={40} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-medium text-primary">{ingredient.name}</div>
        <div className="mt-0.5 font-mono text-[11px] tabular-nums text-tertiary">
          {Math.round(baseGrams * q)} g · {kcal} kcal
        </div>
      </div>
      <label className="shrink-0">
        <span className="mb-1 block text-right text-[10px] text-tertiary">Qty ×</span>
        <input
          type="number"
          min="0"
          step="0.1"
          value={quantity}
          onChange={(e) => onQuantityChange(e.target.value)}
          aria-label={`Quantity for ${ingredient.name}`}
          className="w-20 rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />
      </label>
    </div>
  );
}
