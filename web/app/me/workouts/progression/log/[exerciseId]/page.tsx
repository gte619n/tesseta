import Link from "next/link";
import { getProgressionLog } from "@/lib/progression-api";
import { ProgressionLogTable } from "@/components/workouts/ProgressionLogTable";
import { pageMetadata } from "@/lib/page-metadata";

// Per-lift progression audit page (IMPL-DELOAD-01 D4): the engine's target vs
// the performed top set for every completed session of one exercise, newest
// first, with the plain-English rationale. Linked from the progression
// console's strength card. Read-only, force-dynamic like its siblings.

export const metadata = pageMetadata("Progression Log");

export const dynamic = "force-dynamic";

export default async function ProgressionLogPage({
  params,
}: {
  params: Promise<{ exerciseId: string }>;
}) {
  const { exerciseId } = await params;
  const log = await getProgressionLog(exerciseId).catch(() => null);

  return (
    <div className="space-y-4">
      <Link
        href="/me/workouts/progression"
        className="inline-flex items-center gap-1.5 text-[13px] text-secondary hover:text-accent-dim"
      >
        ← Progression
      </Link>
      {log ? (
        <ProgressionLogTable log={log} />
      ) : (
        <p className="text-[13px] text-secondary">
          Couldn&apos;t load the progression log for this lift.
        </p>
      )}
    </div>
  );
}
