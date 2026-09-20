import { notFound } from "next/navigation";
import { BackendError } from "@/lib/api";
import { getSessionDetail } from "@/lib/workout-stats-api";
import { SessionDetail } from "@/components/workouts/SessionDetail";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workout session");

export const dynamic = "force-dynamic";

// One performed session in full (IMPL-WEB-WORKOUT-01 §4/§5.5). Reached from the
// Overview's latest-workout rows and the consistency heatmap. Read-only; edits
// live on the History page.

type Params = { params: Promise<{ programId: string; scheduledId: string }> };

export default async function SessionDetailPage({ params }: Params) {
  const { programId, scheduledId } = await params;
  const detail = await getSessionDetail(programId, scheduledId).catch((e) => {
    if (e instanceof BackendError && e.status === 404) notFound();
    throw e;
  });

  return (
    <main className="bg-canvas px-8 pb-16 pt-6">
      <div className="mx-auto max-w-[720px]">
        <SessionDetail detail={detail} />
      </div>
    </main>
  );
}
