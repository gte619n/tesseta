import Link from "next/link";
import type { Metadata } from "next";
import { revalidatePath } from "next/cache";
import {
  getDay,
  getTarget,
  addEntry,
  deleteEntry,
  searchFoods,
} from "@/lib/nutrition-api";
import type { Meal, Macros, NutritionDay } from "@/lib/types/nutrition";
import { MEALS } from "@/lib/types/nutrition";
import { MacroProgress } from "@/components/nutrition/MacroProgress";
import { MealSection } from "@/components/nutrition/MealSection";

export const metadata: Metadata = { title: "Nutrition" };
export const dynamic = "force-dynamic";

// Helpers for date navigation (server-side only)
function today(): string {
  return new Date().toISOString().split("T")[0] ?? "";
}

function parseDate(raw: string | string[] | undefined): string {
  if (typeof raw === "string" && /^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw;
  return today();
}

function addDays(date: string, n: number): string {
  const d = new Date(date + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().split("T")[0] ?? date;
}

function formatDisplay(date: string): string {
  const d = new Date(date + "T00:00:00Z");
  const opts: Intl.DateTimeFormatOptions = {
    weekday: "short",
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  };
  return d.toLocaleDateString("en-US", opts);
}

// Build a full day structure with all four meals present (even empty ones)
function buildDay(day: NutritionDay): NutritionDay {
  const existing = new Map(day.meals.map((m) => [m.meal, m]));
  const meals = MEALS.map((meal) =>
    existing.get(meal) ?? {
      meal,
      subtotal: {
        caloriesKcal: 0,
        proteinGrams: 0,
        carbsGrams: 0,
        fatGrams: 0,
        fiberGrams: 0,
        sugarGrams: 0,
      },
      entries: [],
    },
  );
  return { ...day, meals };
}

// Empty day structure for when the backend returns 404 / nothing
function emptyDay(date: string, target: Macros | null): NutritionDay {
  const zero: Macros = {
    caloriesKcal: 0,
    proteinGrams: 0,
    carbsGrams: 0,
    fatGrams: 0,
    fiberGrams: 0,
    sugarGrams: 0,
  };
  return {
    date,
    totals: { ...zero },
    target,
    meals: MEALS.map((meal) => ({
      meal,
      subtotal: { ...zero },
      entries: [],
    })),
  };
}

export default async function NutritionPage(props: {
  searchParams: Promise<{ date?: string }>;
}) {
  const { date: rawDate } = await props.searchParams;
  const date = parseDate(rawDate);
  const isToday = date === today();

  // Pre-fetch server-side
  const [dayResult, target] = await Promise.all([
    getDay(date).catch(() => null),
    getTarget(),
  ]);

  const day = dayResult
    ? buildDay({ ...dayResult, target })
    : emptyDay(date, target);

  // ── Server actions passed as props ──────────────────────────────────

  async function addEntryAction(
    entryDate: string,
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
  ) {
    "use server";
    await addEntry(entryDate, body);
    revalidatePath("/me/nutrition");
  }

  async function deleteEntryAction(entryDate: string, entryId: string) {
    "use server";
    await deleteEntry(entryDate, entryId);
    revalidatePath("/me/nutrition");
  }

  async function searchFoodsAction(q: string) {
    "use server";
    return searchFoods(q);
  }

  const prevDate = addDays(date, -1);
  const nextDate = addDays(date, 1);
  const canGoForward = date < today();

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        {/* Page header */}
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Nutrition
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Daily macro tracking against your targets.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Link
              href="/me/nutrition/history"
              className="caps-mono inline-flex items-center gap-1.5 rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
            >
              <i className="ti ti-chart-bar text-[11px]" aria-hidden />
              History
            </Link>
            <Link
              href="/me/nutrition/target"
              className="caps-mono inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
            >
              <i className="ti ti-target text-[11px]" aria-hidden />
              {target ? "Edit targets" : "Set targets"}
            </Link>
          </div>
        </header>

        {/* Date switcher */}
        <div className="flex items-center gap-2">
          <Link
            href={`/me/nutrition?date=${prevDate}`}
            className="flex h-8 w-8 items-center justify-center rounded-md border-[0.5px] border-border-default bg-canvas text-secondary hover:text-primary"
            aria-label="Previous day"
          >
            <i className="ti ti-chevron-left text-[14px]" aria-hidden />
          </Link>
          <div className="flex-1 text-center">
            <span className="text-[14px] font-medium text-primary">
              {isToday ? "Today" : formatDisplay(date)}
            </span>
            {!isToday && (
              <span className="ml-2 caps-mono text-[10px] text-tertiary">
                {date}
              </span>
            )}
          </div>
          {canGoForward ? (
            <Link
              href={`/me/nutrition?date=${nextDate}`}
              className="flex h-8 w-8 items-center justify-center rounded-md border-[0.5px] border-border-default bg-canvas text-secondary hover:text-primary"
              aria-label="Next day"
            >
              <i className="ti ti-chevron-right text-[14px]" aria-hidden />
            </Link>
          ) : (
            <div className="flex h-8 w-8 items-center justify-center rounded-md border-[0.5px] border-border-default bg-canvas text-quaternary">
              <i className="ti ti-chevron-right text-[14px]" aria-hidden />
            </div>
          )}
        </div>

        {/* Macro progress bars */}
        {!target && (
          <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface px-5 py-4">
            <p className="text-[13px] text-secondary">
              No daily targets set.{" "}
              <Link
                href="/me/nutrition/target"
                className="font-medium text-accent-dim underline-offset-2 hover:underline"
              >
                Set your macro targets
              </Link>{" "}
              to track progress throughout the day.
            </p>
          </div>
        )}
        <MacroProgress totals={day.totals} target={day.target} />

        {/* Meal sections */}
        <div className="space-y-3">
          {day.meals.map((group) => (
            <MealSection
              key={group.meal}
              group={group}
              date={date}
              addEntry={addEntryAction}
              deleteEntry={deleteEntryAction}
              searchFoods={searchFoodsAction}
            />
          ))}
        </div>
      </div>
    </main>
  );
}
