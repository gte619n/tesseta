import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import {
  commitProgramProposal,
  listProgramChatThreads,
  getProgramChatMessages,
  deleteProgramChatThread,
} from "@/lib/workout-program-api";
import { draftToCreateRequest } from "@/lib/workout-program-chat";
import { getLocations } from "@/lib/gym-api";
import { getAvailableExercises } from "@/lib/exercise-admin-api";
import { listGoals } from "@/lib/goals-api";
import { WorkoutProgramChat } from "@/components/workouts/WorkoutProgramChat";
import type {
  CommitProgramActionResult,
  GoalOption,
} from "@/components/workouts/WorkoutProgramChat";
import type { GymOption } from "@/components/workouts/WorkoutProgramProposalCard";
import type { ProgramProposalDraft } from "@/lib/workout-program-chat";
import type {
  WorkoutProgramChatThread,
  WorkoutProgramChatMessage,
  WorkoutProgramChatSchedule,
} from "@/lib/types/workout-program";
import type { ExerciseResponse } from "@/lib/types/exercise";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Program Designer");

export const dynamic = "force-dynamic";

export default async function ProgramChatPage() {
  // Prefetch thread list, gyms (for day/setup pickers), and goals (to link)
  // server-side — backend URL + bearer are server-only. SSE send goes through
  // app/api/workout-programs/chat.
  let threads: WorkoutProgramChatThread[] = [];
  let gyms: GymOption[] = [];
  let goals: GoalOption[] = [];
  try {
    threads = await listProgramChatThreads();
  } catch {
    threads = [];
  }
  try {
    const locations = await getLocations();
    gyms = locations.map((l) => ({ locationId: l.locationId, name: l.name }));
  } catch {
    gyms = [];
  }
  try {
    const all = await listGoals();
    goals = all.map((g) => ({ goalId: g.goalId, title: g.title }));
  } catch {
    goals = [];
  }

  // Commit the (user-edited) proposal to the thread. The thread's fixed
  // schedule is supplied so the request carries the right ScheduleInput. 422
  // returns inline issues so the card can keep editing.
  async function commit(
    threadId: string,
    draft: ProgramProposalDraft,
    schedule: WorkoutProgramChatSchedule | null,
  ): Promise<CommitProgramActionResult> {
    "use server";
    const request = draftToCreateRequest(draft, schedule);
    const result = await commitProgramProposal(threadId, request);
    if (result.ok) {
      revalidatePath("/me/workouts/programs");
      revalidatePath(`/me/workouts/programs/${result.program.programId}`);
      return { ok: true, programId: result.program.programId };
    }
    return { ok: false, issues: result.issues };
  }

  async function loadMessages(
    threadId: string,
  ): Promise<WorkoutProgramChatMessage[]> {
    "use server";
    return getProgramChatMessages(threadId);
  }

  async function loadExercises(
    locationId: string,
  ): Promise<ExerciseResponse[]> {
    "use server";
    if (!locationId) return [];
    return getAvailableExercises(locationId);
  }

  async function deleteThread(threadId: string): Promise<void> {
    "use server";
    await deleteProgramChatThread(threadId);
    revalidatePath("/me/workouts/programs/chat");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[1040px] space-y-6">
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
            Pick your training days and gyms, then describe your goal. The
            assistant proposes an editable periodized program — every prescribed
            exercise is executable at that day&apos;s gym. Review, edit, and save.
          </p>
        </header>

        <WorkoutProgramChat
          initialThreads={threads}
          gyms={gyms}
          goals={goals}
          commit={commit}
          loadMessages={loadMessages}
          loadExercises={loadExercises}
          deleteThread={deleteThread}
        />
      </div>
    </main>
  );
}
