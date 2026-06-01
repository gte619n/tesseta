import Link from "next/link";
import type { Metadata } from "next";
import { revalidatePath } from "next/cache";
import { getTarget, setTarget } from "@/lib/nutrition-api";
import type { Macros } from "@/lib/types/nutrition";
import { TargetForm } from "@/components/nutrition/TargetForm";

export const metadata: Metadata = { title: "Nutrition Targets" };
export const dynamic = "force-dynamic";

export default async function NutritionTargetPage() {
  const current = await getTarget();

  async function setTargetAction(macros: Macros) {
    "use server";
    await setTarget(macros);
    revalidatePath("/me/nutrition");
    revalidatePath("/me/nutrition/target");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[640px] space-y-6">
        <Link
          href="/me/nutrition"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Nutrition
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Daily macro targets
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Set your daily nutrient goals. These appear as progress targets on the
            Nutrition page.
          </p>
        </header>

        {current && (
          <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface px-5 py-4">
            <div className="caps-mono mb-3 text-[9px] tracking-[0.08em] text-tertiary">
              Current targets
            </div>
            <div className="grid grid-cols-3 gap-3 sm:grid-cols-6">
              {[
                { label: "Calories", value: current.caloriesKcal, unit: "kcal" },
                { label: "Protein", value: current.proteinGrams, unit: "g" },
                { label: "Carbs", value: current.carbsGrams, unit: "g" },
                { label: "Fat", value: current.fatGrams, unit: "g" },
                { label: "Fiber", value: current.fiberGrams, unit: "g" },
                { label: "Sugar", value: current.sugarGrams, unit: "g" },
              ].map(({ label, value, unit }) => (
                <div key={label} className="text-center">
                  <div className="font-mono text-[18px] font-medium tabular-nums text-primary">
                    {value != null ? Math.round(value) : "—"}
                  </div>
                  <div className="caps-mono mt-0.5 text-[9px] tracking-[0.06em] text-tertiary">
                    {label}
                    {value != null ? ` (${unit})` : ""}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <TargetForm current={current} setTarget={setTargetAction} />
      </div>
    </main>
  );
}
