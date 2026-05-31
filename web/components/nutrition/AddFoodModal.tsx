"use client";

import { type ReactNode, useRef, useState, useCallback } from "react";
import type { Meal, Macros } from "@/lib/types/nutrition";
import { MEAL_LABELS, QUANTITY_STEPS } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";

type ImageStatus = "NONE" | "PENDING" | "READY" | "FAILED";

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
  meal: Meal;
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
  searchFoods: (q: string) => Promise<FoodResult[]>;
};

type Tab = "catalog" | "quick";

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

export function AddFoodModal({
  isOpen,
  onClose,
  meal,
  date,
  addEntry,
  searchFoods,
}: Props) {
  const toast = useToast();
  const [tab, setTab] = useState<Tab>("catalog");

  // Backdrop close tracking (see web/CLAUDE.md modal pattern)
  const downOnBackdropRef = useRef(false);
  function handleBackdropMouseDown(e: { target: unknown; currentTarget: unknown }) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }
  function handleBackdropClick(e: { target: unknown; currentTarget: unknown }) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
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
        {/* Header */}
        <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-4">
          <h2 className="m-0 text-[16px] font-medium tracking-[-0.01em] text-primary">
            Add food · {MEAL_LABELS[meal]}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded-md p-1 text-tertiary hover:text-primary"
            aria-label="Close"
          >
            <i className="ti ti-x text-[16px]" aria-hidden />
          </button>
        </div>

        {/* Tab switcher */}
        <div className="flex border-b-[0.5px] border-border-subtle px-5">
          <TabButton active={tab === "catalog"} onClick={() => setTab("catalog")}>
            Search catalog
          </TabButton>
          <TabButton active={tab === "quick"} onClick={() => setTab("quick")}>
            Quick add
          </TabButton>
        </div>

        {/* Content */}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {tab === "catalog" ? (
            <CatalogTab
              meal={meal}
              date={date}
              addEntry={addEntry}
              searchFoods={searchFoods}
              onClose={onClose}
              toast={toast}
            />
          ) : (
            <QuickAddTab
              meal={meal}
              date={date}
              addEntry={addEntry}
              onClose={onClose}
              toast={toast}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`caps-mono border-b-2 px-4 py-2.5 text-[10px] tracking-[0.06em] transition-colors ${
        active
          ? "border-accent text-accent-dim"
          : "border-transparent text-tertiary hover:text-secondary"
      }`}
    >
      {children}
    </button>
  );
}

// ── Food image thumbnail ──────────────────────────────────────────────

function FoodImage({
  imageUrl,
  imageStatus,
  size = 40,
}: {
  imageUrl?: string | null;
  imageStatus?: ImageStatus;
  size?: 40 | 48;
}) {
  const sizeClass = size === 48 ? "h-12 w-12" : "h-10 w-10";
  if (imageStatus === "READY" && imageUrl) {
    return (
      <img
        src={imageUrl}
        alt=""
        loading="lazy"
        className={`${sizeClass} shrink-0 rounded-[6px] object-cover`}
      />
    );
  }
  if (imageStatus === "PENDING") {
    return (
      <div
        className={`${sizeClass} shrink-0 animate-pulse rounded-[6px] bg-canvas-sunken`}
        aria-hidden
      />
    );
  }
  // NONE / FAILED / undefined — neutral utensil icon block
  return (
    <div
      className={`${sizeClass} shrink-0 rounded-[6px] bg-canvas-sunken flex items-center justify-center`}
      aria-hidden
    >
      <i className="ti ti-tools-kitchen-2 text-[14px] text-tertiary" />
    </div>
  );
}

// ── Catalog tab ───────────────────────────────────────────────────────

function CatalogTab({
  meal,
  date,
  addEntry,
  searchFoods,
  onClose,
  toast,
}: {
  meal: Meal;
  date: string;
  addEntry: Props["addEntry"];
  searchFoods: Props["searchFoods"];
  onClose: () => void;
  toast: ReturnType<typeof useToast>;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<FoodResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<FoodResult | null>(null);
  const [servingIdx, setServingIdx] = useState(0);
  const [quantity, setQuantity] = useState<(typeof QUANTITY_STEPS)[number]>(1);
  const [saving, setSaving] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleSearch = useCallback(
    (q: string) => {
      setQuery(q);
      if (debounceRef.current) clearTimeout(debounceRef.current);
      if (!q.trim()) {
        setResults([]);
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
      }, 350);
    },
    [searchFoods, toast],
  );

  function selectFood(food: FoodResult) {
    setSelected(food);
    setServingIdx(food.defaultServingIndex);
    setQuantity(1);
  }

  function clearSelection() {
    setSelected(null);
  }

  const serving = selected?.servingSizes[servingIdx];
  const previewMacros =
    selected && serving
      ? computeMacros(selected.macrosPer100g, serving.grams, quantity)
      : null;

  async function handleSave() {
    if (!selected || !serving) return;
    setSaving(true);
    try {
      await addEntry(date, {
        meal,
        foodId: selected.foodId,
        foodName: selected.name,
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
      toast.success(`${selected.name} added to ${MEAL_LABELS[meal]}`);
      onClose();
    } catch {
      toast.error("Failed to add entry");
    } finally {
      setSaving(false);
    }
  }

  if (selected) {
    const servingSizes = selected.servingSizes;
    return (
      <div className="flex flex-col gap-4 p-5">
        {/* Back button */}
        <button
          type="button"
          onClick={clearSelection}
          className="caps-mono inline-flex items-center gap-1 text-[10px] tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          <i className="ti ti-arrow-left text-[12px]" aria-hidden />
          Back to results
        </button>

        {/* Food name + image */}
        <div className="flex items-start gap-3">
          <FoodImage imageUrl={selected.imageUrl} imageStatus={selected.imageStatus} size={48} />
          <div className="min-w-0 flex-1">
            <div className="text-[15px] font-medium text-primary">{selected.name}</div>
            {selected.brand && (
              <div className="mt-0.5 text-[12px] text-secondary">{selected.brand}</div>
            )}
            {selected.source === "OPEN_FOOD_FACTS" && (
              <div className="mt-1 text-[11px] text-tertiary">
                Nutrition data from Open Food Facts (ODbL)
              </div>
            )}
          </div>
        </div>

        {/* Serving size picker */}
        <div>
          <label className="mb-1.5 block text-[11px] font-medium text-secondary">
            Serving size
          </label>
          <div className="flex flex-wrap gap-1.5">
            {servingSizes.map((s, i) => (
              <button
                key={i}
                type="button"
                onClick={() => setServingIdx(i)}
                className={`caps-mono rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
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

        {/* Quantity */}
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
                className={`caps-mono rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] transition-colors ${
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

        {/* Live macro preview */}
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

        {/* Save */}
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

  return (
    <div className="flex flex-col gap-3 p-5">
      {/* Search input */}
      <div className="relative">
        <i className="ti ti-search absolute left-3 top-1/2 -translate-y-1/2 text-[13px] text-tertiary" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(e) => handleSearch(e.target.value)}
          placeholder="Search foods…"
          className="w-full rounded-md border-[0.5px] border-border-default bg-canvas py-2 pl-8 pr-3 text-[13px] text-primary placeholder-tertiary focus:outline-none focus:ring-2 focus:ring-accent"
          autoFocus
        />
      </div>

      {/* Results */}
      {searching && (
        <div className="py-6 text-center text-[13px] text-tertiary">
          Searching…
        </div>
      )}
      {!searching && query && results.length === 0 && (
        <div className="py-6 text-center text-[13px] text-tertiary">
          No foods found for &ldquo;{query}&rdquo;
        </div>
      )}
      {!searching && results.length > 0 && (
        <div className="divide-y divide-border-subtle rounded-[10px] border-[0.5px] border-border-default">
          {results.map((food) => (
            <button
              key={food.foodId}
              type="button"
              onClick={() => selectFood(food)}
              className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-canvas-sunken first:rounded-t-[10px] last:rounded-b-[10px]"
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
      {!query && (
        <div className="py-6 text-center text-[13px] text-tertiary">
          Type to search the food catalog
        </div>
      )}
    </div>
  );
}

// ── Quick add tab ─────────────────────────────────────────────────────

const EMPTY_QUICK: {
  foodName: string;
  caloriesKcal: string;
  proteinGrams: string;
  carbsGrams: string;
  fatGrams: string;
  fiberGrams: string;
  sugarGrams: string;
} = {
  foodName: "",
  caloriesKcal: "",
  proteinGrams: "",
  carbsGrams: "",
  fatGrams: "",
  sugarGrams: "",
  fiberGrams: "",
};

function QuickAddTab({
  meal,
  date,
  addEntry,
  onClose,
  toast,
}: {
  meal: Meal;
  date: string;
  addEntry: Props["addEntry"];
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

  async function handleSave() {
    if (!fields.foodName.trim()) {
      toast.error("Food name is required");
      return;
    }
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
      <p className="text-[13px] text-secondary">
        Enter macros directly without searching the catalog.
      </p>

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
          label="Calories (kcal)"
          value={fields.caloriesKcal}
          onChange={(v) => set("caloriesKcal", v)}
        />
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
      </div>

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
