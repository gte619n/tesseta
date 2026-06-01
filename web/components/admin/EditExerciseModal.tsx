"use client";

import { useEffect, useRef, useState } from 'react';
import { useToast } from '@/components/ui/Toast';
import { EquipmentRequirementPicker } from './EquipmentRequirementPicker';
import type {
  ExerciseResponse,
  ExerciseEditableFields,
  EquipmentRequirement,
  MovementPattern,
  Laterality,
  Mechanic,
  BlockType,
} from '@/lib/types/exercise';
import {
  MOVEMENT_PATTERNS,
  MOVEMENT_PATTERN_LABEL,
  BLOCK_TYPES,
  BLOCK_TYPE_LABEL,
} from '@/lib/types/exercise';
import type { Equipment } from '@/lib/types/gym';

interface Props {
  // null when creating a new (DRAFT) exercise.
  exercise: ExerciseResponse | null;
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  save: (data: ExerciseEditableFields, exerciseId: string | null) => Promise<void>;
  searchEquipment: (search: string) => Promise<Equipment[]>;
  // Resolved equipment names for the exercise's existing requirement ids.
  equipmentNames?: Record<string, string>;
}

const inputCls =
  'w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent';

function listToText(list: string[]): string {
  return list.join(', ');
}

function textToList(text: string): string[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function EditExerciseModal({
  exercise,
  isOpen,
  onClose,
  onSaved,
  save,
  searchEquipment,
  equipmentNames,
}: Props) {
  const toast = useToast();
  const [isSaving, setIsSaving] = useState(false);
  const downOnBackdropRef = useRef(false);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
    }
  }

  const [name, setName] = useState('');
  const [aliases, setAliases] = useState('');
  const [movementPattern, setMovementPattern] = useState<MovementPattern>('OTHER');
  const [primaryMuscles, setPrimaryMuscles] = useState('');
  const [secondaryMuscles, setSecondaryMuscles] = useState('');
  const [laterality, setLaterality] = useState<Laterality>('BILATERAL');
  const [mechanic, setMechanic] = useState<Mechanic>('COMPOUND');
  const [description, setDescription] = useState('');
  const [formCues, setFormCues] = useState('');
  const [requiredEquipment, setRequiredEquipment] = useState<EquipmentRequirement[]>([]);
  const [suitableBlockTypes, setSuitableBlockTypes] = useState<BlockType[]>([]);
  const [repMin, setRepMin] = useState('');
  const [repMax, setRepMax] = useState('');
  const [isTimed, setIsTimed] = useState(false);
  const [demoPromptOverride, setDemoPromptOverride] = useState('');

  useEffect(() => {
    if (!isOpen) return;
    if (exercise) {
      setName(exercise.name);
      setAliases(listToText(exercise.aliases));
      setMovementPattern(exercise.movementPattern);
      setPrimaryMuscles(listToText(exercise.primaryMuscles));
      setSecondaryMuscles(listToText(exercise.secondaryMuscles));
      setLaterality(exercise.laterality);
      setMechanic(exercise.mechanic);
      setDescription(exercise.description);
      setFormCues(exercise.formCues.join('\n'));
      setRequiredEquipment(exercise.requiredEquipment);
      setSuitableBlockTypes(exercise.suitableBlockTypes);
      setRepMin(exercise.defaultRepRange ? String(exercise.defaultRepRange.min) : '');
      setRepMax(exercise.defaultRepRange ? String(exercise.defaultRepRange.max) : '');
      setIsTimed(exercise.isTimed);
      setDemoPromptOverride(exercise.demoPromptOverride ?? '');
    } else {
      setName('');
      setAliases('');
      setMovementPattern('OTHER');
      setPrimaryMuscles('');
      setSecondaryMuscles('');
      setLaterality('BILATERAL');
      setMechanic('COMPOUND');
      setDescription('');
      setFormCues('');
      setRequiredEquipment([]);
      setSuitableBlockTypes([]);
      setRepMin('');
      setRepMax('');
      setIsTimed(false);
      setDemoPromptOverride('');
    }
  }, [isOpen, exercise]);

  if (!isOpen) return null;

  function toggleBlockType(bt: BlockType) {
    setSuitableBlockTypes((prev) =>
      prev.includes(bt) ? prev.filter((b) => b !== bt) : [...prev, bt],
    );
  }

  async function handleSave() {
    if (!name.trim()) {
      toast.error('Name is required');
      return;
    }
    const min = repMin.trim() === '' ? null : Number(repMin);
    const max = repMax.trim() === '' ? null : Number(repMax);
    const defaultRepRange =
      min != null && max != null && !Number.isNaN(min) && !Number.isNaN(max)
        ? { min, max }
        : null;

    setIsSaving(true);
    try {
      await save(
        {
          name: name.trim(),
          aliases: textToList(aliases),
          movementPattern,
          primaryMuscles: textToList(primaryMuscles),
          secondaryMuscles: textToList(secondaryMuscles),
          laterality,
          mechanic,
          description: description.trim(),
          formCues: formCues
            .split('\n')
            .map((s) => s.trim())
            .filter(Boolean),
          requiredEquipment: requiredEquipment.filter((g) => g.anyOf.length > 0),
          suitableBlockTypes,
          defaultRepRange,
          isTimed,
          demoPromptOverride: demoPromptOverride.trim() || null,
        },
        exercise?.exerciseId ?? null,
      );
      toast.success(exercise ? 'Exercise updated' : 'Exercise created');
      onSaved();
    } catch (e) {
      toast.error('Failed to save exercise', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="w-[720px] max-w-[94vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-xl font-semibold text-primary">
          {exercise ? 'Edit exercise' : 'New exercise'}
        </h2>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-primary">Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-primary">
              Aliases (comma-separated)
            </label>
            <input
              value={aliases}
              onChange={(e) => setAliases(e.target.value)}
              placeholder="back squat, high-bar squat"
              className={inputCls}
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-primary">Movement pattern</label>
              <select
                value={movementPattern}
                onChange={(e) => setMovementPattern(e.target.value as MovementPattern)}
                className={inputCls}
              >
                {MOVEMENT_PATTERNS.map((p) => (
                  <option key={p} value={p}>
                    {MOVEMENT_PATTERN_LABEL[p]}
                  </option>
                ))}
              </select>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="mb-1 block text-sm font-medium text-primary">Laterality</label>
                <select
                  value={laterality}
                  onChange={(e) => setLaterality(e.target.value as Laterality)}
                  className={inputCls}
                >
                  <option value="BILATERAL">Bilateral</option>
                  <option value="UNILATERAL">Unilateral</option>
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-primary">Mechanic</label>
                <select
                  value={mechanic}
                  onChange={(e) => setMechanic(e.target.value as Mechanic)}
                  className={inputCls}
                >
                  <option value="COMPOUND">Compound</option>
                  <option value="ISOLATION">Isolation</option>
                </select>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-primary">
                Primary muscles (comma-separated)
              </label>
              <input
                value={primaryMuscles}
                onChange={(e) => setPrimaryMuscles(e.target.value)}
                placeholder="quadriceps, glutes"
                className={inputCls}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-primary">
                Secondary muscles (comma-separated)
              </label>
              <input
                value={secondaryMuscles}
                onChange={(e) => setSecondaryMuscles(e.target.value)}
                className={inputCls}
              />
            </div>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-primary">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              className={inputCls}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-primary">
              Form cues (one per line)
            </label>
            <textarea
              value={formCues}
              onChange={(e) => setFormCues(e.target.value)}
              rows={3}
              placeholder={'Brace your core\nKnees track over toes'}
              className={inputCls}
            />
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-primary">
              Suitable block types
            </label>
            <div className="flex flex-wrap gap-1.5">
              {BLOCK_TYPES.map((bt) => {
                const on = suitableBlockTypes.includes(bt);
                return (
                  <button
                    key={bt}
                    type="button"
                    onClick={() => toggleBlockType(bt)}
                    className={`cursor-pointer rounded-full border px-2.5 py-1 text-[11px] ${
                      on
                        ? 'border-accent bg-accent-bg text-accent-dim'
                        : 'border-border-default bg-canvas text-secondary hover:border-accent'
                    }`}
                  >
                    {BLOCK_TYPE_LABEL[bt]}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="grid grid-cols-3 items-end gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-primary">Default reps min</label>
              <input
                type="number"
                value={repMin}
                onChange={(e) => setRepMin(e.target.value)}
                className={inputCls}
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-primary">Default reps max</label>
              <input
                type="number"
                value={repMax}
                onChange={(e) => setRepMax(e.target.value)}
                className={inputCls}
              />
            </div>
            <label className="flex items-center gap-2 pb-2 text-sm text-primary">
              <input
                type="checkbox"
                checked={isTimed}
                onChange={(e) => setIsTimed(e.target.checked)}
                className="cursor-pointer rounded border-border-default text-accent focus:ring-2 focus:ring-accent"
              />
              Timed (duration, not reps)
            </label>
          </div>

          <div className="rounded-md border border-border-default bg-canvas p-4">
            <h3 className="mb-2 text-sm font-medium text-primary">Equipment requirements</h3>
            <p className="mb-3 text-xs text-tertiary">
              Each group is an any-of set; a gym must satisfy every group for this exercise to be
              executable there.
            </p>
            <EquipmentRequirementPicker
              value={requiredEquipment}
              onChange={setRequiredEquipment}
              searchEquipment={searchEquipment}
              initialNames={equipmentNames}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-primary">
              Demo prompt override (optional)
            </label>
            <textarea
              value={demoPromptOverride}
              onChange={(e) => setDemoPromptOverride(e.target.value)}
              rows={2}
              placeholder="Leave blank to use the built default treatment."
              className={`${inputCls} font-mono text-xs`}
            />
          </div>
        </div>

        <div className="mt-6 flex items-center justify-end gap-2">
          <button
            onClick={onClose}
            disabled={isSaving}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSaving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}
