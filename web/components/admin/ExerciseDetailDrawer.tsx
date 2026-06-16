"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { ExerciseDemoFrames } from "./ExerciseDemoFrames";
import { ReferencePicker } from "./ReferencePicker";
import { RegenerateMediaModal } from "./RegenerateMediaModal";
import { StatusPill, MediaStatusPill } from "./ExercisePills";
import { ReviewedCheckbox } from "./ReviewedCheckbox";
import type { ExerciseResponse, ExerciseSummaryResponse } from "@/lib/types/exercise";
import { MOVEMENT_PATTERN_LABEL } from "@/lib/types/exercise";
import { regenTargets, type ExerciseAdminActions } from "./AdminExerciseReview";

// Catalog-level actions threaded through to the drawer (in addition to the
// per-frame media actions in ExerciseAdminActions).
export interface DrawerActions extends ExerciseAdminActions {
  publish: (exerciseId: string) => Promise<void>;
  archive: (exerciseId: string) => Promise<void>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
  setReviewed: (exerciseId: string, reviewed: boolean) => Promise<void>;
  saveGrounding: (exerciseId: string, imageUrls: string[]) => Promise<void>;
  loadExercise: (exerciseId: string) => Promise<ExerciseResponse>;
}

interface Props extends DrawerActions {
  // The open exercise's id, or null when closed. The drawer lazy-loads full
  // detail via loadExercise on open.
  exerciseId: string | null;
  // Summary list, for the merge target picker (names only).
  summary: ExerciseSummaryResponse[];
  onClose: () => void;
  onEdit: (exercise: ExerciseResponse) => void;
  // Reflect a reviewed toggle back into the container's optimistic map.
  onReviewedChange: (exerciseId: string, reviewed: boolean) => void;
}

// IMPL-20: the shared detail surface opened from a list row or grid tile. Lazy-
// loads full detail (GET /{id}) and hosts the IMPL-19 media/plan editor, the
// regen flow, the grounding picker, the reviewed toggle, and lifecycle actions.
export function ExerciseDetailDrawer({
  exerciseId,
  summary,
  onClose,
  onEdit,
  onReviewedChange,
  publish,
  archive,
  merge,
  setReviewed,
  saveGrounding,
  loadExercise,
  approveMedia,
  regeneratePlan,
  savePlan,
  approvePlan,
  regenerateMedia,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [exercise, setExercise] = useState<ExerciseResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mergeTarget, setMergeTarget] = useState("");

  // Lazy-load full detail whenever a new exercise opens.
  useEffect(() => {
    if (!exerciseId) {
      setExercise(null);
      setMergeTarget("");
      return;
    }
    let cancelled = false;
    setLoading(true);
    setExercise(null);
    loadExercise(exerciseId)
      .then((ex) => {
        if (!cancelled) setExercise(ex);
      })
      .catch((e) => {
        if (!cancelled) {
          toast.error("Failed to load exercise", {
            description: e instanceof Error ? e.message : "Unknown error",
          });
          onClose();
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // toast/onClose are stable enough; re-running only on id change is intended.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [exerciseId, loadExercise]);

  if (!exerciseId) return null;

  async function run(action: () => Promise<void>, ok: string, err: string) {
    setBusy(true);
    try {
      await action();
      toast.success(ok);
      router.refresh();
    } catch (e) {
      toast.error(err, { description: e instanceof Error ? e.message : "Unknown error" });
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleReviewed(next: boolean) {
    if (!exercise) return;
    const id = exercise.exerciseId;
    // Optimistic locally + in the container's map.
    setExercise((cur) => (cur ? { ...cur, reviewed: next } : cur));
    onReviewedChange(id, next);
    try {
      await setReviewed(id, next);
    } catch (e) {
      setExercise((cur) => (cur ? { ...cur, reviewed: !next } : cur));
      onReviewedChange(id, !next);
      toast.error("Failed to update reviewed", {
        description: e instanceof Error ? e.message : "Unknown error",
      });
    }
  }

  const others = summary.filter((c) => c.exerciseId !== exerciseId);

  return (
    <ModalBackdrop
      onClose={onClose}
      contentClassName="flex max-h-[92vh] w-[900px] max-w-[95vw] flex-col overflow-hidden rounded-lg border border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
      {loading || !exercise ? (
        <div className="flex h-64 items-center justify-center text-sm text-tertiary">
          Loading…
        </div>
      ) : (
        <>
          {/* Header */}
          <div className="flex items-start justify-between gap-3 border-b border-border-default px-5 py-4">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="truncate text-lg font-semibold text-primary">
                  {exercise.name}
                </h2>
                <StatusPill status={exercise.status} />
                <MediaStatusPill status={exercise.mediaStatus} />
              </div>
              <p className="mt-0.5 text-xs text-secondary">
                {MOVEMENT_PATTERN_LABEL[exercise.movementPattern]}
                {exercise.primaryMuscles.length > 0
                  ? ` · ${exercise.primaryMuscles.join(", ")}`
                  : ""}
                {exercise.requiredEquipment.length === 0 ? " · bodyweight" : ""}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <ReviewedCheckbox
                reviewed={exercise.reviewed}
                onChange={handleToggleReviewed}
              />
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-2 py-1.5 text-sm text-secondary hover:bg-surface"
              >
                ✕
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {/* Lifecycle actions */}
            <div className="flex flex-wrap items-center gap-1.5">
              <button
                onClick={() => onEdit(exercise)}
                disabled={busy}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
              >
                Edit
              </button>
              {exercise.status !== "PUBLISHED" ? (
                <button
                  onClick={() =>
                    run(() => publish(exercise.exerciseId), "Published", "Failed to publish")
                  }
                  disabled={busy}
                  className="cursor-pointer rounded-md bg-accent px-2.5 py-1.5 text-xs font-medium text-inverse hover:bg-accent/90 disabled:opacity-50"
                >
                  Publish
                </button>
              ) : null}
              {exercise.status !== "ARCHIVED" ? (
                <button
                  onClick={async () => {
                    const ok = await confirm({
                      title: "Archive exercise",
                      description: `Archive "${exercise.name}"? It will be hidden from listings.`,
                      confirmLabel: "Archive",
                      tone: "danger",
                    });
                    if (ok)
                      await run(
                        () => archive(exercise.exerciseId),
                        "Archived",
                        "Failed to archive",
                      );
                  }}
                  disabled={busy}
                  className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
                >
                  Archive
                </button>
              ) : null}
              <div className="ml-auto flex items-center gap-1.5">
                <select
                  value={mergeTarget}
                  onChange={(e) => setMergeTarget(e.target.value)}
                  className="rounded-md border border-border-default bg-canvas px-2 py-1 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent"
                >
                  <option value="">Merge into…</option>
                  {others.map((c) => (
                    <option key={c.exerciseId} value={c.exerciseId}>
                      {c.name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  disabled={!mergeTarget || busy}
                  onClick={async () => {
                    const target = others.find((c) => c.exerciseId === mergeTarget);
                    const ok = await confirm({
                      title: "Merge exercise",
                      description: `Merge "${exercise.name}" into "${target?.name ?? mergeTarget}"? The source becomes an alias of the target.`,
                      confirmLabel: "Merge",
                      tone: "danger",
                    });
                    if (!ok) return;
                    await run(
                      () => merge(exercise.exerciseId, mergeTarget),
                      "Merged",
                      "Failed to merge",
                    );
                    onClose();
                  }}
                  className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
                >
                  Merge
                </button>
              </div>
            </div>

            {/* Media + plan editor */}
            <ExerciseDemoFrames
              exerciseId={exercise.exerciseId}
              exerciseName={exercise.name}
              demoPlan={exercise.demoPlan}
              planStatus={exercise.planStatus}
              frames={exercise.demoFrames}
              mediaStatus={exercise.mediaStatus}
              regeneratePlan={async (id, override) => {
                await regeneratePlan(id, override);
                router.refresh();
              }}
              savePlan={async (id, frames) => {
                await savePlan(id, frames);
                router.refresh();
              }}
              approvePlan={async (id) => {
                await approvePlan(id);
                router.refresh();
              }}
              regenerateFrame={async (id, key) => {
                await regenerateFrame(id, key);
                router.refresh();
              }}
              uploadFrame={async (id, key, file) => {
                await uploadFrame(id, key, file);
                router.refresh();
              }}
              selectFrame={async (id, key, url) => {
                await selectFrame(id, key, url);
                router.refresh();
              }}
              deleteFrame={async (id, key, url) => {
                await deleteFrame(id, key, url);
                router.refresh();
              }}
            />

            {/* Media-level actions: regenerate-all + approve */}
            <MediaActions
              exercise={exercise}
              approveMedia={approveMedia}
              regenerateMedia={regenerateMedia}
              getDemoPrompt={getDemoPrompt}
              saveGrounding={async (id, urls) => {
                await saveGrounding(id, urls);
                // Reflect the new persisted set locally so a reopen pre-selects it.
                setExercise((cur) =>
                  cur ? { ...cur, groundingImageUrls: urls } : cur,
                );
              }}
            />

            {/* Standalone grounding picker (visible even without opening regen) */}
            <div className="rounded-md border border-border-default bg-canvas p-3">
              <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                Pose references (grounding set)
              </span>
              <div className="mt-2">
                <ReferencePicker
                  exercise={exercise}
                  saveGrounding={async (id, urls) => {
                    await saveGrounding(id, urls);
                    setExercise((cur) =>
                      cur ? { ...cur, groundingImageUrls: urls } : cur,
                    );
                  }}
                />
              </div>
            </div>
          </div>
        </>
      )}
    </ModalBackdrop>
  );
}

// Regenerate-all + approve-media controls, with the regen modal (which itself
// hosts the per-run grounding picker).
function MediaActions({
  exercise,
  approveMedia,
  regenerateMedia,
  getDemoPrompt,
  saveGrounding,
}: {
  exercise: ExerciseResponse;
  approveMedia: (exerciseId: string) => Promise<void>;
  regenerateMedia: (
    exerciseId: string,
    promptOverride: string | null,
    key: string | null,
    referenceImageUrls?: string[],
  ) => Promise<void>;
  getDemoPrompt: (exerciseId: string, key: string) => Promise<string>;
  saveGrounding: (exerciseId: string, imageUrls: string[]) => Promise<void>;
}) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [isRegenOpen, setIsRegenOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  async function handleApprove() {
    const ok = await confirm({
      title: "Approve media",
      description: `Mark the demo media for "${exercise.name}" as approved? It becomes visible to users and the program generator.`,
      confirmLabel: "Approve",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await approveMedia(exercise.exerciseId);
      toast.success("Media approved");
      router.refresh();
    } catch (e) {
      toast.error("Failed to approve media", {
        description: e instanceof Error ? e.message : "Unknown error",
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex items-center gap-2">
      <button
        onClick={() => setIsRegenOpen(true)}
        disabled={busy}
        className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
      >
        Regenerate media
      </button>
      {exercise.mediaStatus === "NEEDS_REVIEW" ? (
        <button
          onClick={handleApprove}
          disabled={busy}
          className="cursor-pointer rounded-md bg-green-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-green-700 disabled:opacity-50"
        >
          Approve media
        </button>
      ) : null}

      <RegenerateMediaModal
        exerciseId={exercise.exerciseId}
        exerciseName={exercise.name}
        targets={regenTargets(exercise.demoPlan, exercise.demoFrames)}
        isOpen={isRegenOpen}
        onClose={() => setIsRegenOpen(false)}
        onStarted={() => {
          setIsRegenOpen(false);
          router.refresh();
        }}
        regenerate={regenerateMedia}
        getDemoPrompt={getDemoPrompt}
        exercise={exercise}
        saveGrounding={saveGrounding}
      />
    </div>
  );
}
