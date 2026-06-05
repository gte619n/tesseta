"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import type {
  MealGroup,
  Entry,
  Meal,
  Macros,
  UpdateEntryBody,
  UpdateIngredientBody,
  DescribedMeal,
  LogDescribedMealBody,
} from "@/lib/types/nutrition";
import { MEAL_LABELS } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";
import { MealSection } from "@/components/nutrition/MealSection";

type Props = {
  meals: MealGroup[];
  date: string;
  addEntry: (
    date: string,
    body: {
      meal: Meal;
      foodId: string | null;
      foodName: string;
      servingLabel: string;
      servingGrams: number;
      quantity: number;
      macros: Macros;
      source: "MANUAL" | "CATALOG";
    },
  ) => Promise<void>;
  updateEntry: (
    date: string,
    entryId: string,
    body: UpdateEntryBody,
  ) => Promise<void>;
  updateIngredient: (
    date: string,
    entryId: string,
    index: number,
    body: UpdateIngredientBody,
  ) => Promise<void>;
  deleteEntry: (date: string, entryId: string) => Promise<void>;
  searchFoods: (q: string) => Promise<
    {
      foodId: string;
      name: string;
      brand: string | null;
      macrosPer100g: Macros;
      servingSizes: { label: string; grams: number }[];
      defaultServingIndex: number;
      source: string;
    }[]
  >;
  describeMeal: (description: string) => Promise<DescribedMeal>;
  logDescribedMeal: (
    date: string,
    body: LogDescribedMealBody,
  ) => Promise<void>;
};

const ZERO: Macros = {
  caloriesKcal: 0,
  proteinGrams: 0,
  carbsGrams: 0,
  fatGrams: 0,
  fiberGrams: 0,
  sugarGrams: 0,
};

// Re-sum a meal's subtotal after an entry moves in or out, so the header
// "kcal" chip and the P / C / F / Fiber row stay correct without a refetch.
function sumMacros(entries: Entry[]): Macros {
  return entries.reduce<Macros>(
    (acc, e) => ({
      caloriesKcal: (acc.caloriesKcal ?? 0) + (e.macros.caloriesKcal ?? 0),
      proteinGrams: (acc.proteinGrams ?? 0) + (e.macros.proteinGrams ?? 0),
      carbsGrams: (acc.carbsGrams ?? 0) + (e.macros.carbsGrams ?? 0),
      fatGrams: (acc.fatGrams ?? 0) + (e.macros.fatGrams ?? 0),
      fiberGrams: (acc.fiberGrams ?? 0) + (e.macros.fiberGrams ?? 0),
      sugarGrams: (acc.sugarGrams ?? 0) + (e.macros.sugarGrams ?? 0),
    }),
    { ...ZERO },
  );
}

/**
 * Client wrapper around the meal sections that wires up drag-and-drop: hold the
 * grip on any entry and drop it on another meal to recategorise it. We move the
 * entry optimistically (re-summing both subtotals), then PATCH its `meal` on the
 * server; a failure rolls the move back. Day totals are unaffected by a move.
 */
export function NutritionMeals({
  meals: initialMeals,
  date,
  addEntry,
  updateEntry,
  updateIngredient,
  deleteEntry,
  searchFoods,
  describeMeal,
  logDescribedMeal,
}: Props) {
  const toast = useToast();
  const [meals, setMeals] = useState(initialMeals);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);
  // Re-sync when the server sends fresh data (e.g. after add / edit / delete
  // revalidates the page), so optimistic moves give way to the source of truth.
  useEffect(() => setMeals(initialMeals), [initialMeals]);

  // A small drag distance keeps single clicks (edit / delete) from starting a
  // drag, while still letting a deliberate hold-and-pull pick the entry up.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
  );

  const activeEntry =
    activeId === null
      ? null
      : meals.flatMap((g) => g.entries).find((e) => e.entryId === activeId) ??
        null;

  function handleDragStart(event: DragStartEvent) {
    setActiveId(String(event.active.id));
  }

  async function handleDragEnd(event: DragEndEvent) {
    const entryId = String(event.active.id);
    const targetMeal = event.over ? (String(event.over.id) as Meal) : null;
    setActiveId(null);
    if (!targetMeal) return;

    const source = meals.find((g) =>
      g.entries.some((e) => e.entryId === entryId),
    );
    const moved = source?.entries.find((e) => e.entryId === entryId);
    if (!source || !moved || source.meal === targetMeal) return;

    const previous = meals;
    const next = meals.map((g) => {
      if (g.meal === source.meal) {
        const entries = g.entries.filter((e) => e.entryId !== entryId);
        return { ...g, entries, subtotal: sumMacros(entries) };
      }
      if (g.meal === targetMeal) {
        const entries = [...g.entries, { ...moved, meal: targetMeal }];
        return { ...g, entries, subtotal: sumMacros(entries) };
      }
      return g;
    });
    setMeals(next);

    try {
      await updateEntry(date, entryId, { meal: targetMeal });
      toast.success(`Moved to ${MEAL_LABELS[targetMeal]}`);
    } catch {
      setMeals(previous);
      toast.error("Couldn't move entry");
    }
  }

  return (
    <DndContext
      id="nutrition-meals-dnd"
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      onDragCancel={() => setActiveId(null)}
    >
      <div className="space-y-3">
        {meals.map((group) => (
          <MealSection
            key={group.meal}
            group={group}
            date={date}
            addEntry={addEntry}
            updateEntry={updateEntry}
            updateIngredient={updateIngredient}
            deleteEntry={deleteEntry}
            searchFoods={searchFoods}
            describeMeal={describeMeal}
            logDescribedMeal={logDescribedMeal}
            activeId={activeId}
          />
        ))}
      </div>

      {mounted &&
        createPortal(
          <DragOverlay dropAnimation={null}>
            {activeEntry ? (
              <div className="flex items-center gap-3 rounded-[10px] border-[0.5px] border-accent bg-surface px-4 py-2.5 shadow-[0_16px_40px_rgba(0,0,0,0.18)]">
                <FoodImage
                  imageUrl={activeEntry.imageUrl}
                  imageStatus={activeEntry.imageStatus}
                  size={36}
                />
                <span className="truncate text-[13px] font-medium text-primary">
                  {activeEntry.foodName}
                </span>
                <span className="font-mono text-[12px] tabular-nums text-tertiary">
                  {Math.round(activeEntry.macros.caloriesKcal ?? 0)} kcal
                </span>
              </div>
            ) : null}
          </DragOverlay>,
          document.body,
        )}
    </DndContext>
  );
}
