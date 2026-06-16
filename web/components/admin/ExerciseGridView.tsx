"use client";

import { useState } from "react";
import { StatusPill, MediaStatusPill } from "./ExercisePills";
import { ReviewedCheckbox } from "./ReviewedCheckbox";
import { thumbUrl } from "@/lib/exercise-thumb";
import {
  MOVEMENT_PATTERN_LABEL,
  BLOCK_TYPE_LABEL,
} from "@/lib/types/exercise";
import type { ExerciseSummaryResponse } from "@/lib/types/exercise";

interface Props {
  items: ExerciseSummaryResponse[];
  reviewedFor: (ex: ExerciseSummaryResponse) => boolean;
  onToggleReviewed: (exerciseId: string, reviewed: boolean) => void;
  onOpen: (exerciseId: string) => void;
}

// IMPL-20: tile grid. Each tile shows name, category pills, a thumbnail strip
// of the active frames (variable count), status + reviewed badges, and a
// reviewed checkbox. Tiles open the same detail drawer as the list rows.
export function ExerciseGridView({
  items,
  reviewedFor,
  onToggleReviewed,
  onOpen,
}: Props) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {items.map((ex) => (
        <Tile
          key={ex.exerciseId}
          ex={ex}
          reviewed={reviewedFor(ex)}
          onToggleReviewed={onToggleReviewed}
          onOpen={onOpen}
        />
      ))}
    </div>
  );
}

function Tile({
  ex,
  reviewed,
  onToggleReviewed,
  onOpen,
}: {
  ex: ExerciseSummaryResponse;
  reviewed: boolean;
  onToggleReviewed: (exerciseId: string, reviewed: boolean) => void;
  onOpen: (exerciseId: string) => void;
}) {
  // Only frames that have an active image; thumbnails fall back to the full
  // image on miss (the thumb may not exist yet).
  const images = ex.frameImageUrls.filter((u): u is string => !!u);

  // A div (not a button) so the nested reviewed checkbox stays valid HTML.
  // Keyboard-activatable via role/tabIndex + Enter/Space.
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onOpen(ex.exerciseId)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpen(ex.exerciseId);
        }
      }}
      className="flex cursor-pointer flex-col gap-2 rounded-lg border border-border-default bg-surface p-3 text-left transition-colors hover:border-border-strong hover:bg-canvas focus:outline-none focus:ring-2 focus:ring-accent"
    >
      <div className="flex items-start justify-between gap-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-semibold text-primary">
          {ex.name}
        </h3>
        <div className="flex shrink-0 flex-wrap items-center justify-end gap-1">
          <StatusPill status={ex.status} />
          {reviewed ? (
            <span className="caps-mono rounded-[3px] bg-good-bg px-1.5 py-px text-[9px] tracking-[0.06em] text-accent-dim">
              Reviewed
            </span>
          ) : null}
        </div>
      </div>

      {/* Category pills: movement pattern + workout types. */}
      <div className="flex flex-wrap gap-1">
        <CategoryPill>{MOVEMENT_PATTERN_LABEL[ex.movementPattern]}</CategoryPill>
        {ex.suitableBlockTypes.map((b) => (
          <CategoryPill key={b} muted>
            {BLOCK_TYPE_LABEL[b]}
          </CategoryPill>
        ))}
      </div>

      {/* Thumbnail strip of the active frames (variable count). */}
      {images.length > 0 ? (
        <div className="flex gap-1.5 overflow-x-auto">
          {images.map((url, i) => (
            <Thumb key={`${url}-${i}`} url={url} alt={`${ex.name} frame ${i + 1}`} />
          ))}
        </div>
      ) : (
        <div className="flex aspect-[4/3] w-full flex-col items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
          <i className="ti ti-photo text-xl" aria-hidden />
          <span className="mt-1 text-[10px] uppercase tracking-wider">No frames</span>
        </div>
      )}

      <div className="mt-auto flex items-center justify-between gap-2 pt-1">
        <MediaStatusPill status={ex.mediaStatus} />
        {/* Stop tile-open when toggling reviewed. */}
        <span onClick={(e) => e.stopPropagation()}>
          <ReviewedCheckbox
            reviewed={reviewed}
            onChange={(next) => onToggleReviewed(ex.exerciseId, next)}
          />
        </span>
      </div>
    </div>
  );
}

function CategoryPill({
  children,
  muted,
}: {
  children: React.ReactNode;
  muted?: boolean;
}) {
  return (
    <span
      className={
        "rounded-full border px-2 py-0.5 text-[10px] " +
        (muted
          ? "border-border-default text-tertiary"
          : "border-accent/30 bg-accent-bg text-accent-dim")
      }
    >
      {children}
    </span>
  );
}

// Thumbnail with onError fallback to the original full image. Uses a plain
// <img> (not next/image) because the derived thumb URL may 404 before the
// Cloud Function runs, and next/image's optimizer chokes on missing sources;
// the onError swap to the full image is the documented fallback path.
function Thumb({ url, alt }: { url: string; alt: string }) {
  const [src, setSrc] = useState(() => thumbUrl(url));
  const [fellBack, setFellBack] = useState(false);
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => {
        // First failure: fall back to the full image. Second failure: give up.
        if (!fellBack && src !== url) {
          setFellBack(true);
          setSrc(url);
        }
      }}
      className="h-20 w-16 shrink-0 rounded border border-border-default object-cover"
    />
  );
}
