import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import {
  commitProgramProposal,
  listProgramChatThreads,
  getProgramChatMessages,
  deleteProgramChatThread,
  getProgramDeep,
  getTrtContext,
} from "@/lib/workout-program-api";
import { draftToCreateRequest } from "@/lib/workout-program-chat";
import { getLocations } from "@/lib/gym-api";
import { getAvailableExercises } from "@/lib/exercise-admin-api";
import { listGoals } from "@/lib/goals-api";
import { WorkoutProgramChat } from "@/components/workouts/WorkoutProgramChat";
import type {
  CommitProgramActionResult,
  EditProgramContext,
  GoalOption,
} from "@/components/workouts/WorkoutProgramChat";
import type { GymOption } from "@/components/workouts/WorkoutProgramProposalCard";
import type { ProgramProposalDraft } from "@/lib/workout-program-chat";
import type {
  WorkoutProgramChatThread,
  WorkoutProgramChatMessage,
  WorkoutProgramChatSchedule,
  WorkoutProgramDeepResponse,
  WeekDay,
} from "@/lib/types/workout-program";
import type { TrtContext } from "@/lib/types/trt";
import type { ExerciseResponse } from "@/lib/types/exercise";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Program Designer");

export const dynamic = "force-dynamic";

// Reconstruct the chat schedule (training days + gym per day) from a committed
// program so edit mode can skip the setup form. The program's per-day gyms live
// on each WorkoutDay; the first phase carries the canonical weekly template.
function scheduleFromProgram(
  program: WorkoutProgramDeepResponse,
): WorkoutProgramChatSchedule {
  const dayLocations: Partial<Record<WeekDay, string>> = {};
  for (const phase of program.phases) {
    for (const day of phase.days) {
      if (!dayLocations[day.dayOfWeek]) dayLocations[day.dayOfWeek] = day.locationId;
    }
  }
  return { trainingDays: program.trainingDays, dayLocations };
}

export default async function ProgramChatPage({
  searchParams,
}: {
  searchParams: Promise<{ programId?: string }>;
}) {
  // IMPL-18b: ?programId=… opens the chat to edit that active program in place.
  const { programId: editProgramId } = await searchParams;
  let editProgram: EditProgramContext | undefined;
  if (editProgramId) {
    try {
      const program = await getProgramDeep(editProgramId);
      editProgram = {
        programId: program.programId,
        title: program.title,
        schedule: scheduleFromProgram(program),
        goalId: program.goalId,
      };
    } catch {
      editProgram = undefined;
    }
  }

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

  // TRT / monitoring-panel context for the designer chat (IMPL-18 / ADR-0015).
  // Loaded on mount by the chat component (server-action-as-prop). Returns an
  // empty context on failure so the panel quietly hides.
  async function loadTrtContext(): Promise<TrtContext> {
    "use server";
    try {
      return await getTrtContext();
    } catch {
      return { onTrt: false, markers: [], dangerFlags: [] };
    }
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
            {editProgram ? "Refine your program" : "Program designer"}
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            {editProgram ? (
              <>
                Editing <span className="font-medium">{editProgram.title}</span>.
                Describe what to change — the assistant revises it from today
                forward. Completed sessions stay as they are.
              </>
            ) : (
              <>
                Pick your training days and gyms, then describe your goal. The
                assistant proposes an editable periodized program — every
                prescribed exercise is executable at that day&apos;s gym. Review,
                edit, and save.
              </>
            )}
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
          loadTrtContext={loadTrtContext}
          editProgram={editProgram}
        />
      </div>
    </main>
  );
}
