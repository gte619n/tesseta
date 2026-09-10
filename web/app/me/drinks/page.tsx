import Link from "next/link";
import type { Metadata } from "next";
import { revalidatePath } from "next/cache";
import { BackendError } from "@/lib/api";
import {
  listDrinks,
  analyzeDrink,
  createDrink,
  updateDrink,
  regenerateDrinkImage,
  archiveDrink,
} from "@/lib/drinks-api";
import type { DrinkProposal, SaveDrinkBody } from "@/lib/types/nutrition";
import { DrinksManager } from "@/components/drinks/DrinksManager";

export const metadata: Metadata = { title: "Drinks" };
export const dynamic = "force-dynamic";

export default async function DrinksPage() {
  const drinks = await listDrinks().catch(() => []);

  // ── Server actions passed down to the client manager ────────────────

  // Returns the AI proposal, or null when the analyzer is unavailable / fails
  // (backend 422) — the client then drops the user into manual entry.
  async function analyzeAction(name: string): Promise<DrinkProposal | null> {
    "use server";
    try {
      return await analyzeDrink(name);
    } catch (e) {
      if (e instanceof BackendError && e.status === 422) return null;
      throw e;
    }
  }

  async function createAction(body: SaveDrinkBody) {
    "use server";
    await createDrink(body);
    revalidatePath("/me/drinks");
  }

  async function updateAction(foodId: string, body: SaveDrinkBody) {
    "use server";
    await updateDrink(foodId, body);
    revalidatePath("/me/drinks");
  }

  async function regenerateAction(foodId: string) {
    "use server";
    await regenerateDrinkImage(foodId);
    revalidatePath("/me/drinks");
  }

  async function archiveAction(foodId: string) {
    "use server";
    await archiveDrink(foodId);
    revalidatePath("/me/drinks");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <Link
          href="/me/profile"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Profile
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Drinks
          </h1>
          <p className="mt-1 text-[13px] leading-[1.5] text-secondary">
            Build a personal catalog of alcoholic drinks. Add one at a time —
            let AI estimate the ABV, serving and calories, then review before
            saving. Each saved drink gets a generated picture.
          </p>
        </header>

        <DrinksManager
          drinks={drinks}
          analyze={analyzeAction}
          create={createAction}
          update={updateAction}
          regenerate={regenerateAction}
          archive={archiveAction}
        />
      </div>
    </main>
  );
}
