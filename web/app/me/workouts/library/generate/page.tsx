import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import { AdHocGenerator } from "@/components/workouts/AdHocGenerator";
import { createAdHocWorkout, generateAdHocWorkout } from "@/lib/adhoc-api";
import type {
  AdHocDraft,
  GenerateAdHocRequest,
  GenerateAdHocResponse,
} from "@/lib/types/adhoc";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Generate Workout");
export const dynamic = "force-dynamic";

export default function GenerateAdHocPage() {
  async function generate(
    body: GenerateAdHocRequest,
  ): Promise<GenerateAdHocResponse> {
    "use server";
    return generateAdHocWorkout(body);
  }

  async function save(draft: AdHocDraft, prompt: string): Promise<string> {
    "use server";
    const created = await createAdHocWorkout({
      title: draft.title,
      summary: draft.summary,
      source: "AI_GENERATED",
      prompt,
      equipmentContext: draft.equipmentContext,
      targetDurationMinutes: draft.targetDurationMinutes,
      tags: draft.tags,
      day: draft.day ?? {
        dayId: "",
        label: draft.title,
        dayOfWeek: "MON",
        locationId: "",
        locationName: "",
        orderIndex: 0,
        blocks: [],
      },
    });
    revalidatePath("/me/workouts/library");
    return created.adhocId;
  }

  return (
    <main className="bg-canvas px-8 pb-16 pt-6">
      <div className="mx-auto max-w-[720px]">
        <Link
          href={"/me/workouts/library" as Route}
          className="caps-mono inline-flex items-center gap-1.5 text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Library
        </Link>
        <h1 className="mt-2 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Generate a workout
        </h1>
        <p className="mt-1 text-[13px] text-secondary">
          Describe what you want and where you are. Review it, then save it to your
          library.
        </p>
        <div className="mt-6">
          <AdHocGenerator generateAction={generate} saveAction={save} />
        </div>
      </div>
    </main>
  );
}
