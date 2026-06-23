"use client";

import { useRef, useState, useCallback } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import type {
  Meal,
  Macros,
  ImageStatus,
  Entry,
  LogDescribedMealBody,
  RelogBody,
} from "@/lib/types/nutrition";
import {
  MEAL_LABELS,
  MEALS,
  QUANTITY_STEPS,
  derivedCaloriesKcal,
} from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { FoodImage } from "@/components/nutrition/FoodImage";

type FoodResult = {
  foodId: string;
  name: string;
  brand: string | null;
  macrosPer100g: Macros;
  servingSizes: { label: string; grams: number }[];
  defaultServingIndex: number;
  source: string;
  imageUrl?: string | null;
  imageStatus?: ImageStatus;
};

type Props = {
  isOpen: boolean;
  onClose: () => void;
  /** The pre-selected meal (the section's, or time-inferred); a tappable chip overrides it. */
  initialMeal: Meal;
  date: string;
  recents: Entry[];
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
  searchFoods: (q: string) => Promise<FoodResult[]>;
  describeMealAsync: (date: string, body: LogDescribedMealBody) => Promise<void>;
  relogEntry: (date: string, body: RelogBody) => Promise<void>;
};

function computeMacros(macrosPer100g: Macros, servingGrams: number, quantity: number): Macros {
  const scale = (servingGrams * quantity) / 100;
  return {
    caloriesKcal: macrosPer100g.caloriesKcal !== null ? macrosPer100g.caloriesKcal * scale : null,
    proteinGrams: macrosPer100g.proteinGrams !== null ? macrosPer100g.proteinGrams * scale : null,
    carbsGrams: macrosPer100g.carbsGrams !== null ? macrosPer100g.carbsGrams * scale : null,
    fatGrams: macrosPer100g.fatGrams !== null ? macrosPer100g.fatGrams * scale : null,
    fiberGrams: macrosPer100g.fiberGrams !== null ? macrosPer100g.fiberGrams * scale : null,
    sugarGrams: macrosPer100g.sugarGrams !== null ? macrosPer100g.sugarGrams * scale : null,
  };
}

/**
 * Add-food modal — one unified surface (no tabs):
 *  - the target meal renders as a tappable chip in the header (override picker);
 *  - one search field with an inline trailing spinner;
 *  - an empty query shows the RECENT list (one tap re-logs with the same
 *    portions); a query shows catalog results plus a standing
 *    "Log “…” as a meal" describe row that closes the modal immediately and
 *    lets the entry resolve in the background (the 202 placeholder pattern);
 *  - quick add (manual macros) tucked beneath, with calories derived live
 *    from the macros (4/4/9) — only a macro-less entry keeps a manual kcal.
 */
export function AddFoodModal({
  isOpen,
  onClose,
  initialMeal,
  date,
  recents,
  addEntry,
  searchFoods,
  describeMealAsync,
  relogEntry,
}: Props) {
  const toast = useToast();
  const [meal, setMeal] = useState<Meal>(initialMeal);
  const [mealPickerOpen, setMealPickerOpen] = useState(false);
  const [quickMode, setQuickMode] = useState(false);

  if (!isOpen) return null;

  return (
    <ModalBackdrop
      onClose={onClose}
      contentClassName="flex h-[680px] max-h-[88vh] w-[600px] max-w-[92vw] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
      {/* Header: title + meal chip + close */}
      <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-4">
        <div className="flex items-center gap-3">
          <h2 className="m-0 text-[16px] font-medium tracking-[-0.01em] text-primary">
            Add food
          </h2>
          <button
            type="button"
            onClick={() => setMealPickerOpen((v) => !v)}
            className="caps-mono cursor-pointer rounded-full border-[0.5px] border-border-default bg-canvas px-3 py-1 text-[10px] tracking-[0.06em] text-accent-dim hover:border-border-strong"
            aria-label="Change meal"
          >
            → {MEAL_LABELS[meal]} ▾
          </button>
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

      {mealPickerOpen && (
        <div className="flex gap-1.5 border-b-[0.5px] border-border-subtle px-5 py-3">
          {MEALS.map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => {
                setMeal(m);
                setMealPickerOpen(false);
              }}
              className={`caps-mono cursor-pointer rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                meal === m
                  ? "border-accent bg-accent-bg text-accent-dim"
                  : "border-border-default bg-canvas text-secondary hover:border-border-strong"
              }`}
            >
              {MEAL_LABELS[m]}
            </button>
          ))}
        </div>
      )}

      {/* Content */}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {quickMode ? (
          <QuickAddPane
            meal={meal}
            date={date}
            addEntry={addEntry}
            onBack={() => setQuickMode(false)}
            onClose={onClose}
            toast={toast}
          />
        ) : (
          <SearchPane
            meal={meal}
            date={date}
            recents={recents}
            addEntry={addEntry}
            searchFoods={searchFoods}
            describeMealAsync={describeMealAsync}
            relogEntry={relogEntry}
            onQuickAdd={() => setQuickMode(true)}
            onClose={onClose}
            toast={toast}
          />
        )}
      </div>
    </ModalBackdrop>
  );
}

// ── Unified search / recents / describe pane ─────────────────────────

function SearchPane({
  meal,
  date,
  recents,
  addEntry,
  searchFoods,
  describeMealAsync,
  relogEntry,
  onQuickAdd,
  onClose,
  toast,
}: {
  meal: Meal;
  date: string;
  recents: Entry[];
  addEntry: Props["addEntry"];
  searchFoods: Props["searchFoods"];
  describeMealAsync: Props["describeMealAsync"];
  relogEntry: Props["relogEntry"];
  onQuickAdd: () => void;
  onClose: () => void;
  toast: ReturnType<typeof useToast>;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<FoodResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<FoodResult | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleSearch = useCallback(
    (q: string) => {
      setQuery(q);
      if (debounceRef.current) clearTimeout(debounceRef.current);
      if (!q.trim()) {
        setResults([]);
        setSearching(false);
        return;
      }
      debounceRef.current = setTimeout(async () => {
        setSearching(true);
        try {
          const foods = await searchFoods(q.trim());
          setResults(foods);
        } catch {
          toast.error("Search failed");
        } finally {
          setSearching(false);
        }
      }, 220);
    },
    [searchFoods, toast],
  );

  // Fire-and-forget describe: close immediately; the day view shows the
  // processing placeholder and fills it in (the camera-capture pattern).
  function handleDescribe() {
    const text = query.trim();
    if (!text) return;
    onClose();
    toast.info(`Logging “${text}” to ${MEAL_LABELS[meal]}…`);
    describeMealAsync(date, { description: text, meal }).catch(() => {
      toast.error("Couldn't start that meal — try again");
    });
  }

  // One-tap repeat of a recent entry with the same portions.
  function handleRelog(entry: Entry) {
    if (!entry.date) return;
    onClose();
    relogEntry(date, {
      sourceDate: entry.date,
      sourceEntryId: entry.entryId,
      meal,
    })
      .then(() => toast.success(`${entry.foodName} added to ${MEAL_LABELS[meal]}`))
      .catch(() => toast.error("Failed to add entry"));
  }

  if (selected) {
    return (
      <FoodDetailPane
        food={selected}
        meal={meal}
        date={date}
        addEntry={addEntry}
        onBack={() => setSelected(null)}
        onClose={onClose}
        toast={toast}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3 p-5">
      {/* Search input with inline trailing spinner */}
      <div className="relative">
        <i
          className="ti ti-search absolute left-3 top-1/2 -translate-y-1/2 text-[13px] text-tertiary"
          aria-hidden
        />
        <input
          type="search"
          value={query}
          onChange={(e) => handleSearch(e.target.value)}
          placeholder="Search foods or describe a meal…"
          className="w-full rounded-md border-[0.5px] border-border-default bg-canvas py-2 pl-8 pr-8 text-[13px] text-primary placeholder-tertiary focus:outline-none focus:ring-2 focus:ring-accent"
          autoFocus
          onKeyDown={(e) => {
            if (e.key === "Enter" && query.trim() && results.length === 0 && !searching) {
              handleDescribe();
            }
          }}
        />
        {searching && (
          <i
            className="ti ti-loader-2 absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-[13px] text-tertiary"
            aria-hidden
          />
        )}
      </div>

      {query.trim() === "" ? (
        <>
          <RecentList recents={recents} onRelog={handleRelog} />
          <button
            type="button"
            onClick={onQuickAdd}
            className="caps-mono cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2.5 text-[10px] tracking-[0.06em] text-accent-dim hover:border-border-strong"
          >
            Quick add custom macros
          </button>
        </>
      ) : (
        <>
          {/* First search shows skeleton rows; subsequent keystrokes keep the
              prior results visible (with the inline spinner) to avoid flicker. */}
          {searching && results.length === 0 && <ResultSkeleton />}
          {!searching && results.length === 0 && (
            <div className="py-3 text-center text-[13px] text-tertiary">
              No catalog matches for &ldquo;{query}&rdquo;
            </div>
          )}
          {results.length > 0 && (
            <div className="divide-y divide-border-subtle rounded-[10px] border-[0.5px] border-border-default">
              {results.map((food) => (
                <button
                  key={food.foodId}
                  type="button"
                  onClick={() => setSelected(food)}
                  className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left first:rounded-t-[10px] last:rounded-b-[10px] hover:bg-canvas-sunken"
                >
                  <FoodImage imageUrl={food.imageUrl} imageStatus={food.imageStatus} size={40} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[13px] font-medium text-primary">
                      {food.name}
                    </div>
                    {food.brand && (
                      <div className="caps-mono mt-0.5 text-[9px] tracking-[0.04em] text-tertiary">
                        {food.brand}
                      </div>
                    )}
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono text-[12px] tabular-nums text-secondary">
                      {Math.round(food.macrosPer100g.caloriesKcal ?? 0)}
                      <span className="ml-0.5 text-[10px] text-tertiary">kcal/100g</span>
                    </div>
                    <div className="caps-mono mt-0.5 text-[9px] text-tertiary">
                      P {Math.round(food.macrosPer100g.proteinGrams ?? 0)}g · C{" "}
                      {Math.round(food.macrosPer100g.carbsGrams ?? 0)}g · F{" "}
                      {Math.round(food.macrosPer100g.fatGrams ?? 0)}g
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
          {/* Describe rides along under the results: anything typed can be
              logged as a meal, resolved in the background. */}
          <button
            type="button"
            onClick={handleDescribe}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2.5 text-left text-[13px] text-accent-dim hover:border-border-strong"
          >
            <i className="ti ti-sparkles mr-1.5 text-[12px]" aria-hidden />
            Log &ldquo;{query.trim()}&rdquo; to {MEAL_LABELS[meal]} as a meal
          </button>
        </>
      )}
    </div>
  );
}

function ResultSkeleton() {
  return (
    <div
      className="divide-y divide-border-subtle rounded-[10px] border-[0.5px] border-border-default"
      aria-hidden
    >
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <div className="h-10 w-10 shrink-0 animate-pulse rounded-md bg-canvas-sunken" />
          <div className="min-w-0 flex-1">
            <div className="h-[13px] w-1/2 animate-pulse rounded bg-canvas-sunken" />
            <div className="mt-1.5 h-[9px] w-1/4 animate-pulse rounded bg-canvas-sunken" />
          </div>
          <div className="h-[13px] w-10 shrink-0 animate-pulse rounded bg-canvas-sunken" />
        </div>
      ))}
    </div>
  );
}

function RecentList({
  recents,
  onRelog,
}: {
  recents: Entry[];
  onRelog: (entry: Entry) => void;
}) {
  if (recents.length === 0) {
    return (
      <div className="py-6 text-center text-[13px] text-tertiary">
        Foods you log will show up here for one-tap repeats.
      </div>
    );
  }
  return (
    <div>
      <div className="caps-mono mb-1.5 text-[9px] tracking-[0.08em] text-tertiary">
        Recent
      </div>
      <div className="divide-y divide-border-subtle rounded-[10px] border-[0.5px] border-border-default">
        {recents.map((entry) => (
          <button
            key={`${entry.date}/${entry.entryId}`}
            type="button"
            onClick={() => onRelog(entry)}
            className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left first:rounded-t-[10px] last:rounded-b-[10px] hover:bg-canvas-sunken"
          >
            <FoodImage imageUrl={entry.imageUrl} imageStatus={entry.imageStatus} size={40} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-medium text-primary">
                {entry.foodName}
              </div>
              {entry.servingLabel && (
                <div className="caps-mono mt-0.5 text-[9px] tracking-[0.04em] text-tertiary">
                  {entry.servingLabel}
                  {entry.quantity !== 1 && ` × ${entry.quantity}`}
                </div>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <span className="font-mono text-[12px] tabular-nums text-secondary">
                {Math.round(entry.macros.caloriesKcal ?? 0)}
                <span className="ml-0.5 text-[10px] text-tertiary">kcal</span>
              </span>
              <i className="ti ti-plus text-[14px] text-accent-dim" aria-hidden />
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

// ── Catalog food detail (serving + quantity) ─────────────────────────

function FoodDetailPane({
  food,
  meal,
  date,
  addEntry,
  onBack,
  onClose,
  toast,
}: {
  food: FoodResult;
  meal: Meal;
  date: string;
  addEntry: Props["addEntry"];
  onBack: () => void;
  onClose: () => void;
  toast: ReturnType<typeof useToast>;
}) {
  const [servingIdx, setServingIdx] = useState(food.defaultServingIndex);
  const [quantity, setQuantity] = useState<(typeof QUANTITY_STEPS)[number]>(1);
  const [saving, setSaving] = useState(false);

  const serving = food.servingSizes[servingIdx];
  const previewMacros = serving
    ? computeMacros(food.macrosPer100g, serving.grams, quantity)
    : null;

  async function handleSave() {
    if (!serving) return;
    setSaving(true);
    try {
      await addEntry(date, {
        meal,
        foodId: food.foodId,
        foodName: food.name,
        servingLabel: serving.label,
        servingGrams: serving.grams,
        quantity,
        macros: previewMacros ?? {
          caloriesKcal: null,
          proteinGrams: null,
          carbsGrams: null,
          fatGrams: null,
          fiberGrams: null,
          sugarGrams: null,
        },
        source: "CATALOG",
      });
      toast.success(`${food.name} added to ${MEAL_LABELS[meal]}`);
      onClose();
    } catch {
      toast.error("Failed to add entry");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-4 p-5">
      <button
        type="button"
        onClick={onBack}
        className="caps-mono inline-flex cursor-pointer items-center gap-1 text-[10px] tracking-[0.04em] text-tertiary hover:text-secondary"
      >
        <i className="ti ti-arrow-left text-[12px]" aria-hidden />
        Back to results
      </button>

      <div className="flex items-start gap-3">
        <FoodImage imageUrl={food.imageUrl} imageStatus={food.imageStatus} size={48} />
        <div className="min-w-0 flex-1">
          <div className="text-[15px] font-medium text-primary">{food.name}</div>
          {food.brand && (
            <div className="mt-0.5 text-[12px] text-secondary">{food.brand}</div>
          )}
          {food.source === "OPEN_FOOD_FACTS" && (
            <div className="mt-1 text-[11px] text-tertiary">
              Nutrition data from Open Food Facts (ODbL)
            </div>
          )}
        </div>
      </div>

      <div>
        <label className="mb-1.5 block text-[11px] font-medium text-secondary">
          Serving size
        </label>
        <div className="flex flex-wrap gap-1.5">
          {food.servingSizes.map((s, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setServingIdx(i)}
              className={`caps-mono cursor-pointer rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                servingIdx === i
                  ? "border-accent bg-accent-bg text-accent-dim"
                  : "border-border-default bg-canvas text-secondary hover:border-border-strong"
              }`}
            >
              {s.label}
              <span className="ml-1 text-tertiary">({s.grams}g)</span>
            </button>
          ))}
        </div>
      </div>

      <div>
        <label className="mb-1.5 block text-[11px] font-medium text-secondary">
          Quantity
        </label>
        <div className="flex gap-1.5">
          {QUANTITY_STEPS.map((q) => (
            <button
              key={q}
              type="button"
              onClick={() => setQuantity(q)}
              className={`caps-mono cursor-pointer rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
                quantity === q
                  ? "border-accent bg-accent-bg text-accent-dim"
                  : "border-border-default bg-canvas text-secondary hover:border-border-strong"
              }`}
            >
              {q}×
            </button>
          ))}
        </div>
      </div>

      {previewMacros && (
        <div className="rounded-[10px] bg-canvas-sunken px-4 py-3">
          <div className="caps-mono mb-2 text-[9px] tracking-[0.08em] text-tertiary">
            Macro preview
          </div>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
            {[
              { label: "Calories", value: previewMacros.caloriesKcal, unit: "kcal" },
              { label: "Protein", value: previewMacros.proteinGrams, unit: "g" },
              { label: "Carbs", value: previewMacros.carbsGrams, unit: "g" },
              { label: "Fat", value: previewMacros.fatGrams, unit: "g" },
              { label: "Fiber", value: previewMacros.fiberGrams, unit: "g" },
              { label: "Sugar", value: previewMacros.sugarGrams, unit: "g" },
            ].map(({ label, value, unit }) => (
              <div key={label} className="text-center">
                <div className="font-mono text-[16px] font-medium tabular-nums text-primary">
                  {value !== null ? Math.round(value) : "—"}
                </div>
                <div className="caps-mono text-[8px] tracking-[0.06em] text-tertiary">
                  {label}
                  {value !== null ? ` (${unit})` : ""}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="flex justify-end gap-2">
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
          {saving ? "Saving…" : "Add to log"}
        </button>
      </div>
    </div>
  );
}

// ── Quick add (manual macros, derived calories) ──────────────────────

const EMPTY_QUICK = {
  foodName: "",
  caloriesKcal: "",
  proteinGrams: "",
  carbsGrams: "",
  fatGrams: "",
  fiberGrams: "",
  sugarGrams: "",
};

function QuickAddPane({
  meal,
  date,
  addEntry,
  onBack,
  onClose,
  toast,
}: {
  meal: Meal;
  date: string;
  addEntry: Props["addEntry"];
  onBack: () => void;
  onClose: () => void;
  toast: ReturnType<typeof useToast>;
}) {
  const [fields, setFields] = useState({ ...EMPTY_QUICK });
  const [saving, setSaving] = useState(false);

  function set(key: keyof typeof EMPTY_QUICK, value: string) {
    setFields((prev) => ({ ...prev, [key]: value }));
  }

  function numOr(s: string): number | null {
    const n = parseFloat(s);
    return isNaN(n) ? null : n;
  }

  // Calories are derived from the macros (4/4/9, the backend's invariant) the
  // moment any macro is typed; with no macros the user may enter kcal directly.
  const hasMacros =
    fields.proteinGrams.trim() !== "" ||
    fields.carbsGrams.trim() !== "" ||
    fields.fatGrams.trim() !== "";
  const derived = derivedCaloriesKcal(
    numOr(fields.proteinGrams),
    numOr(fields.carbsGrams),
    numOr(fields.fatGrams),
  );

  async function handleSave() {
    if (!fields.foodName.trim()) {
      toast.error("Food name is required");
      return;
    }
    const macros: Macros = {
      caloriesKcal: hasMacros ? derived : numOr(fields.caloriesKcal),
      proteinGrams: numOr(fields.proteinGrams),
      carbsGrams: numOr(fields.carbsGrams),
      fatGrams: numOr(fields.fatGrams),
      fiberGrams: numOr(fields.fiberGrams),
      sugarGrams: numOr(fields.sugarGrams),
    };
    setSaving(true);
    try {
      await addEntry(date, {
        meal,
        foodId: null,
        foodName: fields.foodName.trim(),
        servingLabel: "1 serving",
        servingGrams: 100,
        quantity: 1,
        macros,
        source: "MANUAL",
      });
      toast.success(`${fields.foodName} added to ${MEAL_LABELS[meal]}`);
      onClose();
    } catch {
      toast.error("Failed to add entry");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-4 p-5">
      <button
        type="button"
        onClick={onBack}
        className="caps-mono inline-flex cursor-pointer items-center gap-1 text-[10px] tracking-[0.04em] text-tertiary hover:text-secondary"
      >
        <i className="ti ti-arrow-left text-[12px]" aria-hidden />
        Back
      </button>

      <div>
        <label className="mb-1 block text-[11px] font-medium text-secondary">
          Food name *
        </label>
        <input
          value={fields.foodName}
          onChange={(e) => set("foodName", e.target.value)}
          placeholder="e.g. Protein shake"
          className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
          autoFocus
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <MacroField
          label="Protein (g)"
          value={fields.proteinGrams}
          onChange={(v) => set("proteinGrams", v)}
        />
        <MacroField
          label="Carbs (g)"
          value={fields.carbsGrams}
          onChange={(v) => set("carbsGrams", v)}
        />
        <MacroField
          label="Fat (g)"
          value={fields.fatGrams}
          onChange={(v) => set("fatGrams", v)}
        />
        <MacroField
          label="Fiber (g)"
          value={fields.fiberGrams}
          onChange={(v) => set("fiberGrams", v)}
        />
        <MacroField
          label="Sugar (g)"
          value={fields.sugarGrams}
          onChange={(v) => set("sugarGrams", v)}
        />
        {!hasMacros && (
          <MacroField
            label="Calories (kcal)"
            value={fields.caloriesKcal}
            onChange={(v) => set("caloriesKcal", v)}
          />
        )}
      </div>

      {hasMacros && (
        <div className="rounded-[10px] bg-canvas-sunken px-4 py-2.5 font-mono text-[12px] tabular-nums text-secondary">
          = {Math.round(derived ?? 0)} kcal{" "}
          <span className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
            computed from macros
          </span>
        </div>
      )}

      <div className="flex justify-end gap-2">
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
          {saving ? "Saving…" : "Add to log"}
        </button>
      </div>
    </div>
  );
}

function MacroField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="mb-1 block text-[11px] font-medium text-secondary">
        {label}
      </label>
      <input
        type="number"
        min="0"
        step="0.1"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="0"
        className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent"
      />
    </div>
  );
}
