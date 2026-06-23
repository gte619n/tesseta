"use client";

import { Fragment, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type {
  ScheduledWorkoutResponse,
  PrescriptionExercise,
  CompleteSessionRequest,
  WorkoutHistoryPage,
} from "@/lib/types/workout-program";
import { ExerciseDetailSheet } from "./ExerciseDetailSheet";
import { LogSessionModal } from "./LogSessionModal";
import { BLOCK_TYPE_LABEL } from "@/lib/types/exercise";
import {
  formatDuration,
  formatLoggedSets,
  totalLoggedSets,
} from "@/lib/workout-format";

function formatWhen(session: ScheduledWorkoutResponse): string {
  const iso = session.completedAt ?? `${session.date}T00:00:00`;
  const d = new Date(iso);
  return d
    .toLocaleDateString("en-US", {
      weekday: "short",
      month: "short",
      day: "numeric",
      year: "numeric",
    })
    .toUpperCase();
}

function formatClock(completedAt: string | null): string | null {
  if (!completedAt) return null;
  return new Date(completedAt).toLocaleTimeString("en-US", {
    hour: "numeric",
    minute: "2-digit",
  });
}

// True when this row opens a new program/phase run — i.e. its program or phase
// differs from the previous (more recent) row. Used to draw delineation headers.
function startsNewGroup(
  session: ScheduledWorkoutResponse,
  prev: ScheduledWorkoutResponse | undefined,
): boolean {
  if (!prev) return true;
  return prev.programId !== session.programId || prev.phaseId !== session.phaseId;
}

export function WorkoutHistoryList({
  firstPage,
  loadMore,
  logSession,
}: {
  firstPage: WorkoutHistoryPage;
  // Server action: fetch the next page of history (25 rows, newest first).
  loadMore: (page: number) => Promise<WorkoutHistoryPage>;
  // Server action: completion upsert addressed by program + scheduled id —
  // the after-the-fact "edit actuals" path (ADR-0012 D6). Rows without a
  // programId (older backend responses) simply don't offer the edit.
  logSession: (
    programId: string,
    scheduledId: string,
    input: CompleteSessionRequest,
  ) => Promise<void>;
}) {
  const router = useRouter();
  const [sheetExercise, setSheetExercise] = useState<PrescriptionExercise | null>(
    null,
  );
  const [editTarget, setEditTarget] = useState<ScheduledWorkoutResponse | null>(
    null,
  );

  // Accumulate pages as the user loads more. `firstPage` seeds the list and
  // resets it on a router.refresh() (e.g. after editing actuals).
  const [sessions, setSessions] = useState(firstPage.items);
  const [nextPage, setNextPage] = useState(firstPage.page + 1);
  const [hasMore, setHasMore] = useState(firstPage.hasMore);
  const [seeded, setSeeded] = useState(firstPage);
  const [pending, startTransition] = useTransition();

  // Re-seed when a fresh first page arrives (server re-render / refresh).
  if (seeded !== firstPage) {
    setSeeded(firstPage);
    setSessions(firstPage.items);
    setNextPage(firstPage.page + 1);
    setHasMore(firstPage.hasMore);
  }

  function onLoadMore() {
    startTransition(async () => {
      const page = await loadMore(nextPage);
      setSessions((prev) => [...prev, ...page.items]);
      setNextPage(page.page + 1);
      setHasMore(page.hasMore);
    });
  }

  if (sessions.length === 0) {
    return (
      <p className="text-[13px] text-secondary">
        No completed workouts yet. Sessions you finish — or import — show up here.
      </p>
    );
  }

  return (
    <>
      <div className="space-y-2">
        {sessions.map((s, i) => (
          <Fragment key={`${s.programId ?? "?"}-${s.scheduledId}`}>
            {startsNewGroup(s, sessions[i - 1]) ? <GroupHeader session={s} /> : null}
            <HistoryRow
              session={s}
              onOpenExercise={setSheetExercise}
              onEdit={s.programId ? setEditTarget : null}
            />
          </Fragment>
        ))}
      </div>
      {hasMore ? (
        <div className="mt-4 flex justify-center">
          <button
            type="button"
            onClick={onLoadMore}
            disabled={pending}
            className="caps-mono cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[11px] tracking-[0.06em] text-secondary hover:text-primary disabled:cursor-default disabled:opacity-50"
          >
            {pending ? "Loading…" : `Load more (${sessions.length} of ${firstPage.total})`}
          </button>
        </div>
      ) : (
        <p className="caps-mono mt-4 text-center text-[10px] tracking-[0.06em] text-tertiary">
          {sessions.length} workout{sessions.length === 1 ? "" : "s"}
        </p>
      )}
      <ExerciseDetailSheet
        exercise={sheetExercise}
        onClose={() => setSheetExercise(null)}
      />
      <LogSessionModal
        session={editTarget}
        onClose={() => setEditTarget(null)}
        onSaved={() => {
          setEditTarget(null);
          router.refresh();
        }}
        save={(input) =>
          logSession(editTarget!.programId!, editTarget!.scheduledId, input)
        }
      />
    </>
  );
}

// Delineation header between program/phase runs in the time-ordered list.
function GroupHeader({ session }: { session: ScheduledWorkoutResponse }) {
  const program = session.programTitle?.trim();
  const phase = session.phaseTitle?.trim();
  return (
    <div className="flex items-baseline gap-2 pt-4 first:pt-0">
      <span className="caps-mono text-[11px] font-medium tracking-[0.06em] text-secondary">
        {program || "Workout"}
      </span>
      {phase ? (
        <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
          · {phase}
        </span>
      ) : null}
    </div>
  );
}

function HistoryRow({
  session,
  onOpenExercise,
  onEdit,
}: {
  session: ScheduledWorkoutResponse;
  onOpenExercise: (e: PrescriptionExercise) => void;
  onEdit: ((s: ScheduledWorkoutResponse) => void) | null;
}) {
  const [open, setOpen] = useState(false);
  const setCount = totalLoggedSets(session);
  const clock = formatClock(session.completedAt);

  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-canvas px-4 py-3">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex min-w-0 flex-1 items-center justify-between gap-3 text-left"
        >
          <div className="min-w-0">
            <span className="text-[14px] font-medium text-primary">
              {session.dayLabel}
            </span>
            <div className="caps-mono mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[10px] tracking-[0.06em] text-tertiary">
              <span>{formatWhen(session)}</span>
              {clock ? <span>· {clock}</span> : null}
              {session.durationSeconds != null ? (
                <span>· {formatDuration(session.durationSeconds)}</span>
              ) : null}
              {setCount > 0 ? (
                <span>
                  · {setCount} set{setCount === 1 ? "" : "s"}
                </span>
              ) : null}
            </div>
          </div>
          <span className="caps-mono shrink-0 text-[10px] tracking-[0.06em] text-tertiary">
            {open ? "−" : "+"}
          </span>
        </button>
        {onEdit ? (
          <button
            type="button"
            onClick={() => onEdit(session)}
            className="caps-mono shrink-0 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
          >
            Edit
          </button>
        ) : null}
      </div>

      {open ? (
        <div className="mt-3 space-y-2 border-t-[0.5px] border-border-subtle pt-3">
          {session.session.blocks.map((block) => (
            <div key={block.blockId}>
              <div className="flex items-center gap-2">
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-secondary">
                  {BLOCK_TYPE_LABEL[block.type]}
                </span>
                <span className="text-[12px] font-medium text-primary">
                  {block.title}
                </span>
              </div>
              <ul className="mt-1 space-y-0.5">
                {block.prescriptions.map((p, i) => {
                  const ex = p.exercise;
                  const name = ex?.name ?? p.notes ?? p.exerciseId;
                  const logged = formatLoggedSets(p);
                  return (
                    <li key={i}>
                      <button
                        type="button"
                        disabled={!ex}
                        onClick={() => ex && onOpenExercise(ex)}
                        className={`flex w-full items-start justify-between gap-3 rounded px-2 py-1 text-left ${
                          ex ? "cursor-pointer hover:bg-canvas-muted" : ""
                        }`}
                      >
                        <span className="min-w-0 text-[13px] text-primary">
                          {name}
                          {logged ? (
                            <span className="ml-2 text-[12px] text-tertiary">
                              {logged}
                            </span>
                          ) : null}
                        </span>
                        {ex ? (
                          <span className="shrink-0 text-[11px] text-tertiary">
                            View →
                          </span>
                        ) : null}
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
