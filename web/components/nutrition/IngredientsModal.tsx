"use client";

import { useRef, useState } from "react";
import type {
  Entry,
  EntryIngredient,
  Macros,
  UpdateEntryBody,
  UpdateIngredientBody,
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
 * quantity multiplier (the portion size itself is fixed). One Save commits the
 * title and all quantity changes together.
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
  const [saving, setSaving] = useState(false);

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

  async function handleSave() {
    setSaving(true);
    try {
      const nextTitle = title.trim();
      if (nextTitle && nextTitle !== entry.foodName) {
        await updateEntry(date, entry.entryId, { foodName: nextTitle });
      }
      for (let i = 0; i < ingredients.length; i++) {
        const newQ = parseFloat(qtys[i] ?? "") || 1;
        const oldQ = ingredients[i]?.quantity ?? 1;
        if (newQ > 0 && newQ !== oldQ) {
          await updateIngredient(date, entry.entryId, i, { quantity: newQ });
        }
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
        {/* Header: finished-meal image + total */}
        <div className="flex items-start justify-between gap-3 border-b-[0.5px] border-border-subtle px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <FoodImage imageUrl={entry.imageUrl} imageStatus={entry.imageStatus} size={64} />
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[16px] font-medium tracking-[-0.01em] text-primary">
                {title.trim() || entry.foodName}
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
      </div>
    </div>
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
