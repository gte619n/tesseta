// IMPL-20: shared client-side filter/sort layer for the exercise catalog. Both
// the list and grid views consume the same filtered/sorted array produced here.
// The 352-item summary projection is small enough to filter in memory (the spec
// defers server-side filtering/pagination until well above that).

import type {
  ExerciseSummaryResponse,
  ExerciseStatus,
  ExerciseMediaStatus,
  MovementPattern,
  BlockType,
} from "@/lib/types/exercise";

export type SortKey = "name" | "imageCount";

// "Recently updated" isn't available on the slim summary (no updatedAt), so the
// sort control offers name and image count; the container can pre-sort by the
// server's natural order otherwise. See decision log.

export type ReviewedFilter = "any" | "yes" | "no";

// Image-count buckets: exact 0/1/2 and "3 or more".
export type ImageCountBucket = "any" | "0" | "1" | "2" | "3+";

export type CatalogFilters = {
  search: string;
  status: ExerciseStatus | "any";
  mediaStatus: ExerciseMediaStatus | "any";
  planStatus: ExerciseMediaStatus | "any";
  reviewed: ReviewedFilter;
  imageCount: ImageCountBucket;
  movementPattern: MovementPattern | "any";
  blockType: BlockType | "any";
};

export const EMPTY_FILTERS: CatalogFilters = {
  search: "",
  status: "any",
  mediaStatus: "any",
  planStatus: "any",
  reviewed: "any",
  imageCount: "any",
  movementPattern: "any",
  blockType: "any",
};

// IMPL-20: the Review tab folds into a "Needs review" preset over the catalog
// (mediaStatus === NEEDS_REVIEW || planStatus === NEEDS_REVIEW). Because the
// filter bar AND-combines fields, this preset is applied as a dedicated flag
// rather than via the status selects.
export type CatalogPreset = "needs-review" | null;

// How many active frame images an exercise has (non-null entries).
export function activeImageCount(ex: ExerciseSummaryResponse): number {
  return ex.frameImageUrls.filter((u) => !!u).length;
}

function matchesImageBucket(count: number, bucket: ImageCountBucket): boolean {
  switch (bucket) {
    case "any":
      return true;
    case "0":
      return count === 0;
    case "1":
      return count === 1;
    case "2":
      return count === 2;
    case "3+":
      return count >= 3;
  }
}

function matchesPreset(ex: ExerciseSummaryResponse, preset: CatalogPreset): boolean {
  if (preset === "needs-review") {
    return ex.mediaStatus === "NEEDS_REVIEW" || ex.planStatus === "NEEDS_REVIEW";
  }
  return true;
}

export function applyFilters(
  catalog: ExerciseSummaryResponse[],
  filters: CatalogFilters,
  preset: CatalogPreset,
): ExerciseSummaryResponse[] {
  const q = filters.search.trim().toLowerCase();
  return catalog.filter((ex) => {
    if (!matchesPreset(ex, preset)) return false;
    if (q) {
      const hay =
        ex.name.toLowerCase() +
        " " +
        ex.movementPattern.toLowerCase() +
        " " +
        ex.primaryMuscles.join(" ").toLowerCase();
      if (!hay.includes(q)) return false;
    }
    if (filters.status !== "any" && ex.status !== filters.status) return false;
    if (filters.mediaStatus !== "any" && ex.mediaStatus !== filters.mediaStatus)
      return false;
    if (filters.planStatus !== "any" && ex.planStatus !== filters.planStatus)
      return false;
    if (filters.reviewed === "yes" && !ex.reviewed) return false;
    if (filters.reviewed === "no" && ex.reviewed) return false;
    if (!matchesImageBucket(activeImageCount(ex), filters.imageCount)) return false;
    if (
      filters.movementPattern !== "any" &&
      ex.movementPattern !== filters.movementPattern
    )
      return false;
    if (
      filters.blockType !== "any" &&
      !ex.suitableBlockTypes.includes(filters.blockType)
    )
      return false;
    return true;
  });
}

export function sortCatalog(
  catalog: ExerciseSummaryResponse[],
  sort: SortKey,
): ExerciseSummaryResponse[] {
  const next = [...catalog];
  switch (sort) {
    case "name":
      next.sort((a, b) => a.name.localeCompare(b.name));
      break;
    case "imageCount":
      // Most images first — surfaces well-built exercises (and, inverted by the
      // user's mental model, makes thin ones easy to spot at the bottom).
      next.sort((a, b) => activeImageCount(b) - activeImageCount(a));
      break;
  }
  return next;
}

export function hasActiveFilters(filters: CatalogFilters): boolean {
  return (
    filters.status !== "any" ||
    filters.mediaStatus !== "any" ||
    filters.planStatus !== "any" ||
    filters.reviewed !== "any" ||
    filters.imageCount !== "any" ||
    filters.movementPattern !== "any" ||
    filters.blockType !== "any"
  );
}
