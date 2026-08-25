import Link from "next/link";
import { revalidatePath } from "next/cache";
import {
  getWorkoutSettings,
  saveWorkoutPreferences,
} from "@/lib/workout-program-api";
import { WorkoutPreferencesForm } from "@/components/workouts/WorkoutPreferencesForm";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workout Preferences");

export const dynamic = "force-dynamic";

export default async function WorkoutPreferencesPage() {
  const settings = await getWorkoutSettings().catch(() => ({
    weeklyStreakTarget: null,
    preferences: null,
  }));

  async function save(preferences: string) {
    "use server";
    await saveWorkoutPreferences(preferences);
    revalidatePath("/me/workouts/preferences");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[720px] space-y-6">
        <Link
          href="/me/workouts"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Workouts
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Workout preferences
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Standing notes the program builder follows every time it designs or
            revises a program — exercises to avoid, injuries to work around, or
            how you like to train.
          </p>
        </header>

        <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
          <WorkoutPreferencesForm
            preferences={settings.preferences}
            saveAction={save}
          />
        </section>
      </div>
    </main>
  );
}
