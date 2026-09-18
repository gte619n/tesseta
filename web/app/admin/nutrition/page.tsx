import { dedupeFoods } from "@/lib/nutrition-admin-api";
import { FoodDedupeClient } from "@/components/admin/FoodDedupeClient";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Nutrition Admin");

export default async function AdminNutritionPage() {
  // Admin gating handled by app/admin/layout.tsx.
  async function dedupeAction(): Promise<number> {
    "use server";
    return dedupeFoods();
  }

  return (
    <div className="container mx-auto max-w-3xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-primary">Nutrition catalog</h1>
        <p className="mt-1 text-sm text-secondary">
          One-off maintenance for the shared food catalog.
        </p>
      </div>
      <FoodDedupeClient dedupe={dedupeAction} />
    </div>
  );
}
