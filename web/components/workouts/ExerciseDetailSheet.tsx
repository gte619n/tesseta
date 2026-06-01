"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { PrescriptionExercise } from "@/lib/types/workout-program";
import { DEMO_PHASES, DEMO_PHASE_LABEL } from "@/lib/types/exercise";

type Props = {
  exercise: PrescriptionExercise | null;
  onClose: () => void;
};

// A bottom/side sheet showing an exercise's demo phase stills, form cues, and
// primary muscles. Reuses the modal-backdrop trio from web/CLAUDE.md.
export function ExerciseDetailSheet({ exercise, onClose }: Props) {
  const downOnBackdropRef = useRef(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!exercise) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [exercise, onClose]);

  if (!mounted || !exercise) return null;

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }
  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) onClose();
  }

  const frameByPhase = new Map(exercise.demoFrames.map((f) => [f.phase, f]));

  return createPortal(
    <div
      className="fixed inset-0 z-[200] flex items-end justify-center bg-canvas/75 backdrop-blur-sm sm:items-center"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="max-h-[88vh] w-full max-w-[560px] overflow-y-auto rounded-t-[16px] border-[0.5px] border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)] sm:rounded-[16px]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <h2 className="m-0 text-[18px] font-medium tracking-[-0.01em] text-primary">
            {exercise.name}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="cursor-pointer rounded-md px-2 py-1 text-[16px] leading-none text-tertiary hover:text-secondary"
          >
            ×
          </button>
        </div>

        {exercise.primaryMuscles.length > 0 ? (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {exercise.primaryMuscles.map((m) => (
              <span
                key={m}
                className="caps-mono rounded-full border-[0.5px] border-border-default bg-canvas px-2 py-0.5 text-[10px] tracking-[0.04em] text-secondary"
              >
                {m}
              </span>
            ))}
          </div>
        ) : null}

        {/* Demo phase stills */}
        <div className="mt-4 grid grid-cols-3 gap-2">
          {DEMO_PHASES.map((phase) => {
            const frame = frameByPhase.get(phase);
            return (
              <div key={phase} className="flex flex-col gap-1">
                <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                  {DEMO_PHASE_LABEL[phase]}
                </span>
                {frame?.imageUrl ? (
                  <div className="relative aspect-[4/5] w-full overflow-hidden rounded-md border-[0.5px] border-border-default">
                    <Image
                      src={frame.imageUrl}
                      alt={`${exercise.name} ${phase}`}
                      fill
                      sizes="(max-width: 560px) 33vw, 180px"
                      className="object-cover"
                    />
                  </div>
                ) : (
                  <div className="flex aspect-[4/5] w-full items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
                    <span className="text-[10px] uppercase tracking-wider">No frame</span>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {exercise.formCues.length > 0 ? (
          <div className="mt-4 border-t-[0.5px] border-border-subtle pt-3">
            <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
              Form cues
            </span>
            <ul className="mt-1.5 list-disc space-y-1 pl-4 text-[13px] leading-[1.45] text-secondary">
              {exercise.formCues.map((cue, i) => (
                <li key={i}>{cue}</li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </div>,
    document.body,
  );
}
