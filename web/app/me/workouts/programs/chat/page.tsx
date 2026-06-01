import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import { commitProgramProposal } from "@/lib/workout-program-api";
import { proposalToCreateRequest } from "@/lib/workout-program-chat";
import { WorkoutProgramChat } from "@/components/workouts/WorkoutProgramChat";
import type { CommitProgramActionResult } from "@/components/workouts/WorkoutProgramChat";
import type { ProgramProposalDraft } from "@/components/workouts/WorkoutProgramProposalCard";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Program Designer");

export const dynamic = "force-dynamic";

export default function ProgramChatPage() {
  // The SSE send goes through app/api/workout-programs/chat. Commit is plain
  // JSON, defined here as a server action and passed to the client component.
  async function commit(draft: ProgramProposalDraft): Promise<CommitProgramActionResult> {
    "use server";
    const request = proposalToCreateRequest(draft.proposal);
    const result = await commitProgramProposal(request);
    if (result.ok) {
      revalidatePath("/me/workouts/programs");
      revalidatePath(`/me/workouts/programs/${result.program.programId}`);
      return { ok: true, programId: result.program.programId };
    }
    return { ok: false, issues: result.issues };
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href={"/me/workouts/programs" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Programs
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Program designer
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Describe your goal, training days, and gyms. The assistant proposes an
            editable periodized program — every prescribed exercise is executable at that
            day&apos;s gym. Review the validator notes, then save.
          </p>
        </header>

        <WorkoutProgramChat commit={commit} />
      </div>
    </main>
  );
}
