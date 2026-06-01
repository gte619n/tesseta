"use client";

import { useRef, useState } from "react";
import type {
  Entry,
  EntryIngredient,
  Macros,
  UpdateIngredientBody,
} from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";

type Props = {
  isOpen: boolean;
  onClose: () => void;
  entry: Entry;
  date: string;
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
 * Modal for a composite (photo-logged) meal: the finished-meal image plus each
 * ingredient with its raw-ingredient image and an editable portion. Editing a
 * portion re-scales that ingredient server-side and the meal total recomputes.
 */
export function IngredientsModal({
  isOpen,
  onClose,
  entry,
  date,
  updateIngredient,
}: Props) {
  // Backdrop close tracking (see web/CLAUDE.md modal pattern)
  const downOnBackdropRef = useRef(false);
  function handleBackdropMouseDown(e: { target: unknown; currentTarget: unknown }) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }
  function handleBackdropClick(e: { target: unknown; currentTarget: unknown }) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) onClose();
  }

  if (!isOpen) return null;
  const ingredients = entry.ingredients ?? [];

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="flex max-h-[90vh] w-[560px] max-w-[92vw] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header: finished-meal image + name + total */}
        <div className="flex items-start justify-between gap-3 border-b-[0.5px] border-border-subtle px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <FoodImage imageUrl={entry.imageUrl} imageStatus={entry.imageStatus} size={64} />
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[16px] font-medium tracking-[-0.01em] text-primary">
                {entry.foodName}
              </h2>
              <div className="mt-1 font-mono text-[12px] tabular-nums text-secondary">
                {Math.round(num(entry.macros.caloriesKcal))} kcal · {ingredients.length}{" "}
                ingredients
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

        {/* Ingredients */}
        <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-5">
          {ingredients.map((ing, index) => (
            <IngredientRow
              key={`${ing.foodId ?? ing.name}-${index}`}
              ingredient={ing}
              onSave={(body) => updateIngredient(date, entry.entryId, index, body)}
            />
          ))}
        </div>

        <div className="flex justify-end border-t-[0.5px] border-border-subtle px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}

function IngredientRow({
  ingredient,
  onSave,
}: {
  ingredient: EntryIngredient;
  onSave: (body: UpdateIngredientBody) => Promise<void>;
}) {
  const toast = useToast();
  const [grams, setGrams] = useState(String(num(ingredient.servingGrams)));
  const [qty, setQty] = useState(String(ingredient.quantity ?? 1));
  const [saving, setSaving] = useState(false);

  const g = parseFloat(grams) || 0;
  const q = parseFloat(qty) || 1;
  const kcal = scaleKcal(ingredient.macrosPer100g, g, q);

  async function handleSave() {
    if (g <= 0) {
      toast.error("Grams must be greater than 0");
      return;
    }
    setSaving(true);
    try {
      await onSave({
        servingGrams: g,
        quantity: q,
        servingLabel: ingredient.servingLabel ?? undefined,
      });
      toast.success(`${ingredient.name} updated`);
    } catch {
      toast.error("Failed to update portion");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas p-3">
      <div className="flex items-center gap-3">
        <FoodImage imageUrl={ingredient.imageUrl} imageStatus={ingredient.imageStatus} size={40} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] font-medium text-primary">{ingredient.name}</div>
          <div className="mt-0.5 font-mono text-[11px] tabular-nums text-tertiary">
            {Math.round(g * q)} g · {kcal} kcal
          </div>
        </div>
      </div>
      <div className="mt-2.5 flex items-end gap-2">
        <label className="flex-1">
          <span className="mb-1 block text-[10px] text-tertiary">Grams</span>
          <input
            type="number"
            min="0"
            step="1"
            value={grams}
            onChange={(e) => setGrams(e.target.value)}
            className="w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          />
        </label>
        <label className="flex-1">
          <span className="mb-1 block text-[10px] text-tertiary">Quantity (×)</span>
          <input
            type="number"
            min="0"
            step="0.1"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            className="w-full rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          />
        </label>
        <button
          type="button"
          onClick={handleSave}
          disabled={saving}
          className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? "…" : "Update"}
        </button>
      </div>
    </div>
  );
}
