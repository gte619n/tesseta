"use client";

import { StatusPill, MediaStatusPill } from "./ExercisePills";
import { ReviewedCheckbox } from "./ReviewedCheckbox";
import { activeImageCount } from "./catalog-filters";
import { MOVEMENT_PATTERN_LABEL } from "@/lib/types/exercise";
import type { ExerciseSummaryResponse } from "@/lib/types/exercise";

interface Props {
  items: ExerciseSummaryResponse[];
  // reviewed state, optimistically overridden in the container.
  reviewedFor: (ex: ExerciseSummaryResponse) => boolean;
  onToggleReviewed: (exerciseId: string, reviewed: boolean) => void;
  onOpen: (exerciseId: string) => void;
}

// IMPL-20: slim list rows over the summary projection. Clicking a row opens the
// shared detail drawer (which lazy-loads full detail + media editor). The
// heavy expandable-media row from IMPL-14/19 now lives in the drawer.
export function ExerciseListView({
  items,
  reviewedFor,
  onToggleReviewed,
  onOpen,
}: Props) {
  return (
    <div className="overflow-hidden rounded-lg border border-border-default bg-surface">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border-default text-left">
            <th className="px-4 py-2 text-xs font-medium text-tertiary">Name</th>
            <th className="px-3 py-2 text-xs font-medium text-tertiary">Pattern</th>
            <th className="px-3 py-2 text-xs font-medium text-tertiary">Status</th>
            <th className="px-3 py-2 text-xs font-medium text-tertiary">Media</th>
            <th className="px-3 py-2 text-center text-xs font-medium text-tertiary">
              Images
            </th>
            <th className="px-3 py-2 text-center text-xs font-medium text-tertiary">
              Reviewed
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((ex) => (
            <tr
              key={ex.exerciseId}
              onClick={() => onOpen(ex.exerciseId)}
              className="cursor-pointer border-b border-border-subtle last:border-b-0 hover:bg-canvas"
            >
              <td className="px-4 py-2.5">
                <span className="font-medium text-primary">{ex.name}</span>
                {ex.primaryMuscles.length > 0 ? (
                  <span className="ml-2 text-xs text-tertiary">
                    {ex.primaryMuscles.join(", ")}
                  </span>
                ) : null}
              </td>
              <td className="px-3 py-2.5 text-xs text-secondary">
                {MOVEMENT_PATTERN_LABEL[ex.movementPattern]}
              </td>
              <td className="px-3 py-2.5">
                <StatusPill status={ex.status} />
              </td>
              <td className="px-3 py-2.5">
                <MediaStatusPill status={ex.mediaStatus} />
              </td>
              <td className="px-3 py-2.5 text-center text-xs tabular text-secondary">
                {activeImageCount(ex)}/{ex.frameCount}
              </td>
              {/* Stop row-open when toggling the checkbox. */}
              <td
                className="px-3 py-2.5 text-center"
                onClick={(e) => e.stopPropagation()}
              >
                <ReviewedCheckbox
                  reviewed={reviewedFor(ex)}
                  onChange={(next) => onToggleReviewed(ex.exerciseId, next)}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
