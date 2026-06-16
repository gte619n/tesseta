"use client";

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { EditExerciseModal } from './EditExerciseModal';
import { RegenerateMediaModal } from './RegenerateMediaModal';
import { ExerciseDemoFrames } from './ExerciseDemoFrames';
import { StatusPill, MediaStatusPill } from './ExercisePills';
import type {
  ExerciseResponse,
  ExerciseEditableFields,
} from '@/lib/types/exercise';
import { MOVEMENT_PATTERN_LABEL } from '@/lib/types/exercise';
import type { Equipment } from '@/lib/types/gym';
import { regenTargets, type ExerciseAdminActions } from './AdminExerciseReview';

interface Props extends ExerciseAdminActions {
  catalog: ExerciseResponse[];
  equipmentNames: Record<string, string>;
  save: (data: ExerciseEditableFields, exerciseId: string | null) => Promise<void>;
  searchEquipment: (search: string) => Promise<Equipment[]>;
  publish: (exerciseId: string) => Promise<void>;
  archive: (exerciseId: string) => Promise<void>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
}

export function AdminExerciseCatalog({
  catalog,
  equipmentNames,
  save,
  searchEquipment,
  publish,
  archive,
  merge,
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
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<ExerciseResponse | null>(null);
  const [creating, setCreating] = useState(false);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return catalog;
    return catalog.filter(
      (ex) =>
        ex.name.toLowerCase().includes(q) ||
        ex.movementPattern.toLowerCase().includes(q) ||
        ex.primaryMuscles.some((m) => m.toLowerCase().includes(q)) ||
        ex.aliases.some((a) => a.toLowerCase().includes(q)),
    );
  }, [catalog, search]);

  return (
    <>
      <div className="mb-4 flex items-center gap-3">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name, muscle, or pattern…"
          className="flex-1 rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />
        <button
          type="button"
          onClick={() => setCreating(true)}
          className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90"
        >
          New exercise
        </button>
      </div>

      {filtered.length === 0 ? (
        <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
          <p className="text-sm text-secondary">No exercises match.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((ex) => (
            <CatalogRow
              key={ex.exerciseId}
              exercise={ex}
              catalog={catalog}
              onEdit={() => setEditing(ex)}
              publish={publish}
              archive={archive}
              merge={merge}
              regeneratePlan={regeneratePlan}
              savePlan={savePlan}
              approvePlan={approvePlan}
              regenerateMedia={regenerateMedia}
              regenerateFrame={regenerateFrame}
              uploadFrame={uploadFrame}
              selectFrame={selectFrame}
              deleteFrame={deleteFrame}
              approveMedia={approveMedia}
              getDemoPrompt={getDemoPrompt}
            />
          ))}
        </div>
      )}

      <EditExerciseModal
        exercise={editing}
        isOpen={editing !== null}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          router.refresh();
        }}
        save={save}
        searchEquipment={searchEquipment}
        equipmentNames={equipmentNames}
      />
      <EditExerciseModal
        exercise={null}
        isOpen={creating}
        onClose={() => setCreating(false)}
        onSaved={() => {
          setCreating(false);
          router.refresh();
        }}
        save={save}
        searchEquipment={searchEquipment}
        equipmentNames={equipmentNames}
      />
    </>
  );
}

function CatalogRow({
  exercise,
  catalog,
  onEdit,
  publish,
  archive,
  merge,
  regeneratePlan,
  savePlan,
  approvePlan,
  regenerateMedia,
  regenerateFrame,
  uploadFrame,
  selectFrame,
  deleteFrame,
  getDemoPrompt,
}: {
  exercise: ExerciseResponse;
  catalog: ExerciseResponse[];
  onEdit: () => void;
} & Pick<
  Props,
  | 'publish'
  | 'archive'
  | 'merge'
  | 'regeneratePlan'
  | 'savePlan'
  | 'approvePlan'
  | 'regenerateMedia'
  | 'regenerateFrame'
  | 'uploadFrame'
  | 'selectFrame'
  | 'deleteFrame'
  | 'approveMedia'
  | 'getDemoPrompt'
>) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [expanded, setExpanded] = useState(false);
  const [isRegenOpen, setIsRegenOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>, successMsg: string, errMsg: string) {
    setBusy(true);
    try {
      await action();
      toast.success(successMsg);
      router.refresh();
    } catch (e) {
      toast.error(errMsg, { description: e instanceof Error ? e.message : 'Unknown error' });
    } finally {
      setBusy(false);
    }
  }

  const [mergeOpen, setMergeOpen] = useState(false);
  const [mergeTarget, setMergeTarget] = useState('');

  function handleMerge() {
    const others = catalog.filter((c) => c.exerciseId !== exercise.exerciseId);
    if (others.length === 0) {
      toast.info('Nothing to merge into');
      return;
    }
    // Merge is offered through a lightweight inline select (no window.prompt
    // per design rules); the admin picks the canonical target by name below.
    setMergeOpen(true);
  }

  return (
    <>
      <div className="rounded-lg border border-border-default bg-surface p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="truncate text-sm font-semibold text-primary">{exercise.name}</h3>
              <StatusPill status={exercise.status} />
              <MediaStatusPill status={exercise.mediaStatus} />
            </div>
            <p className="mt-0.5 text-xs text-secondary">
              {MOVEMENT_PATTERN_LABEL[exercise.movementPattern]}
              {exercise.primaryMuscles.length > 0
                ? ` · ${exercise.primaryMuscles.join(', ')}`
                : ''}
              {exercise.requiredEquipment.length === 0 ? ' · bodyweight' : ''}
            </p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
            <button
              onClick={() => setExpanded((v) => !v)}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface"
            >
              {expanded ? 'Hide media' : 'Media'}
            </button>
            <button
              onClick={onEdit}
              disabled={busy}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
            >
              Edit
            </button>
            <button
              onClick={() => setIsRegenOpen(true)}
              disabled={busy}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
            >
              Regenerate
            </button>
            {exercise.status !== 'PUBLISHED' ? (
              <button
                onClick={() =>
                  run(() => publish(exercise.exerciseId), 'Published', 'Failed to publish')
                }
                disabled={busy}
                className="cursor-pointer rounded-md bg-accent px-2.5 py-1.5 text-xs font-medium text-inverse hover:bg-accent/90 disabled:opacity-50"
              >
                Publish
              </button>
            ) : null}
            {exercise.status !== 'ARCHIVED' ? (
              <button
                onClick={async () => {
                  const ok = await confirm({
                    title: 'Archive exercise',
                    description: `Archive "${exercise.name}"? It will be hidden from listings.`,
                    confirmLabel: 'Archive',
                    tone: 'danger',
                  });
                  if (ok) await run(() => archive(exercise.exerciseId), 'Archived', 'Failed to archive');
                }}
                disabled={busy}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
              >
                Archive
              </button>
            ) : null}
            <button
              onClick={handleMerge}
              disabled={busy}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:opacity-50"
            >
              Merge
            </button>
          </div>
        </div>

        {mergeOpen ? (
          <div className="mt-3 flex items-center gap-2 rounded-md border border-border-default bg-canvas p-2">
            <span className="text-xs text-secondary">Merge into:</span>
            <select
              value={mergeTarget}
              onChange={(e) => setMergeTarget(e.target.value)}
              className="flex-1 rounded-md border border-border-default bg-surface px-2 py-1 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            >
              <option value="">Select a canonical exercise…</option>
              {catalog
                .filter((c) => c.exerciseId !== exercise.exerciseId)
                .map((c) => (
                  <option key={c.exerciseId} value={c.exerciseId}>
                    {c.name}
                  </option>
                ))}
            </select>
            <button
              type="button"
              disabled={!mergeTarget || busy}
              onClick={async () => {
                const target = catalog.find((c) => c.exerciseId === mergeTarget);
                const ok = await confirm({
                  title: 'Merge exercise',
                  description: `Merge "${exercise.name}" into "${target?.name ?? mergeTarget}"? The source becomes an alias of the target.`,
                  confirmLabel: 'Merge',
                  tone: 'danger',
                });
                if (!ok) return;
                await run(
                  () => merge(exercise.exerciseId, mergeTarget),
                  'Merged',
                  'Failed to merge',
                );
                setMergeOpen(false);
              }}
              className="cursor-pointer rounded-md bg-accent px-3 py-1 text-xs font-medium text-inverse disabled:opacity-50"
            >
              Confirm
            </button>
            <button
              type="button"
              onClick={() => setMergeOpen(false)}
              className="cursor-pointer rounded-md border border-border-default px-2 py-1 text-xs text-primary"
            >
              Cancel
            </button>
          </div>
        ) : null}

        {expanded ? (
          <div className="mt-4 border-t border-border-subtle pt-4">
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
          </div>
        ) : null}
      </div>

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
      />
    </>
  );
}
