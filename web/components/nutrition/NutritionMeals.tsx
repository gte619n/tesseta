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
  LogDescribedMealBody,
  MealSearchResult,
  RelogBody,
  AdjustApplyBody,
  AdjustPreviewResponse,
} from "@/lib/types/nutrition";
import { MEAL_LABELS } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";
import { MealSection } from "@/components/nutrition/MealSection";
import { formatWholeNumber } from "@/lib/format-number";
import {
  addEntryOffline,
  deleteEntryOffline,
  updateEntryOffline,
} from "@/lib/offline/writes";

// Add/edit/delete of entries route through the offline outbox (client-side) with
// optimistic updates below, so those three are no longer server-action props.
type AddEntryFields = {
  meal: Meal;
  foodId: string | null;
  foodName: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  macros: Macros;
  source: "MANUAL" | "CATALOG";
};

type Props = {
  meals: MealGroup[];
  date: string;
  updateIngredient: (
    date: string,
    entryId: string,
    index: number,
    body: UpdateIngredientBody,
  ) => Promise<void>;
  regenerateImage: (date: string, entryId: string) => Promise<void>;
  servingHint: (date: string, entryId: string) => Promise<string | null>;
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
  deleteFood: (foodId: string) => Promise<void>;
  searchMeals: (q: string) => Promise<MealSearchResult[]>;
  describeMealAsync: (
    date: string,
    body: LogDescribedMealBody,
  ) => Promise<void>;
  logMeal: (date: string, body: LogDescribedMealBody) => Promise<void>;
  relogEntry: (date: string, body: RelogBody) => Promise<void>;
  adjustPreview: (
    date: string,
    entryId: string,
    instruction: string,
  ) => Promise<AdjustPreviewResponse>;
  adjustApply: (date: string, entryId: string, body: AdjustApplyBody) => Promise<void>;
  recents: Entry[];
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
 * Apply a patch to an entry wherever it lives, moving it to `patch.meal`'s group
 * if the meal changed, and re-summing every affected subtotal. Handles both a
 * field edit and a drag-move. Returns the groups unchanged if the id isn't found.
 */
function patchEntryInGroups(
  groups: MealGroup[],
  entryId: string,
  patch: Partial<Entry>,
): MealGroup[] {
  let moved: Entry | null = null;
  const withoutEntry = groups.map((g) => {
    const found = g.entries.find((e) => e.entryId === entryId);
    if (!found) return g;
    moved = { ...found, ...patch };
    const entries = g.entries.filter((e) => e.entryId !== entryId);
    return { ...g, entries, subtotal: sumMacros(entries) };
  });
  if (moved === null) return groups;
  const target: Entry = moved;
  return withoutEntry.map((g) =>
    g.meal === target.meal
      ? { ...g, entries: [...g.entries, target], subtotal: sumMacros([...g.entries, target]) }
      : g,
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
  updateIngredient,
  regenerateImage,
  servingHint,
  searchFoods,
  deleteFood,
  searchMeals,
  describeMealAsync,
  logMeal,
  relogEntry,
  adjustPreview,
  adjustApply,
  recents,
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

  // Add/edit/delete now go through the offline outbox: apply an optimistic
  // update to the local `meals` state, then journal the mutation (survives a
  // flaky network / tab close). The OutboxDrainer's router.refresh() after a
  // successful drain re-syncs `meals` to server truth via the initialMeals
  // effect above. On the rare journaling failure we roll back.

  /** Insert an optimistic entry into its meal group. Client-minted id. */
  async function handleAdd(entryDate: string, body: AddEntryFields): Promise<void> {
    const id = crypto.randomUUID();
    const optimistic: Entry = {
      entryId: id,
      meal: body.meal,
      foodId: body.foodId,
      foodName: body.foodName,
      servingLabel: body.servingLabel,
      servingGrams: body.servingGrams,
      quantity: body.quantity,
      macros: body.macros,
      source: body.source,
      imageUrl: null,
      imageStatus: "NONE",
      createdAt: null, // placeholder until the drain + refresh brings server truth
    };
    const previous = meals;
    setMeals((groups) =>
      groups.map((g) =>
        g.meal === body.meal
          ? { ...g, entries: [...g.entries, optimistic], subtotal: sumMacros([...g.entries, optimistic]) }
          : g,
      ),
    );
    try {
      await addEntryOffline(entryDate, { id, ...body });
    } catch {
      setMeals(previous);
      toast.error("Couldn't log entry");
    }
  }

  /** Optimistically patch an entry (fields and/or its meal group) then queue it. */
  async function handleUpdate(
    entryDate: string,
    entryId: string,
    body: UpdateEntryBody,
  ): Promise<void> {
    const previous = meals;
    setMeals((groups) => patchEntryInGroups(groups, entryId, body));
    try {
      await updateEntryOffline(entryDate, entryId, body);
    } catch {
      setMeals(previous);
      toast.error("Couldn't save changes");
    }
  }

  /** Optimistically drop an entry then queue the delete. */
  async function handleDelete(entryDate: string, entryId: string): Promise<void> {
    const previous = meals;
    setMeals((groups) =>
      groups.map((g) => {
        const entries = g.entries.filter((e) => e.entryId !== entryId);
        return entries.length === g.entries.length
          ? g
          : { ...g, entries, subtotal: sumMacros(entries) };
      }),
    );
    try {
      await deleteEntryOffline(entryDate, entryId);
    } catch {
      setMeals(previous);
      toast.error("Couldn't remove entry");
    }
  }

  async function handleDragEnd(event: DragEndEvent) {
    const entryId = String(event.active.id);
    const targetMeal = event.over ? (String(event.over.id) as Meal) : null;
    setActiveId(null);
    if (!targetMeal) return;

    const source = meals.find((g) =>
      g.entries.some((e) => e.entryId === entryId),
    );
    if (!source || source.meal === targetMeal) return;

    const previous = meals;
    setMeals((groups) => patchEntryInGroups(groups, entryId, { meal: targetMeal }));
    try {
      await updateEntryOffline(date, entryId, { meal: targetMeal });
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
            addEntry={handleAdd}
            updateEntry={handleUpdate}
            updateIngredient={updateIngredient}
            deleteEntry={handleDelete}
            regenerateImage={regenerateImage}
            servingHint={servingHint}
            searchFoods={searchFoods}
            deleteFood={deleteFood}
            searchMeals={searchMeals}
            describeMealAsync={describeMealAsync}
            logMeal={logMeal}
            relogEntry={relogEntry}
            adjustPreview={adjustPreview}
            adjustApply={adjustApply}
            recents={recents}
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
                  imageUrl={activeEntry.photoUrl ?? activeEntry.imageUrl}
                  imageStatus={activeEntry.imageStatus}
                  size={36}
                />
                <span className="truncate text-[13px] font-medium text-primary">
                  {activeEntry.foodName}
                </span>
                <span className="font-mono text-[12px] tabular-nums text-tertiary">
                  {formatWholeNumber(activeEntry.macros.caloriesKcal ?? 0)} kcal
                </span>
              </div>
            ) : null}
          </DragOverlay>,
          document.body,
        )}
    </DndContext>
  );
}
