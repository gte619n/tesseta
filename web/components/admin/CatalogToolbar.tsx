"use client";

import {
  MOVEMENT_PATTERNS,
  MOVEMENT_PATTERN_LABEL,
  BLOCK_TYPES,
  BLOCK_TYPE_LABEL,
} from "@/lib/types/exercise";
import type {
  ExerciseStatus,
  ExerciseMediaStatus,
  MovementPattern,
  BlockType,
} from "@/lib/types/exercise";
import {
  EMPTY_FILTERS,
  hasActiveFilters,
  type CatalogFilters,
  type SortKey,
  type ReviewedFilter,
  type ImageCountBucket,
} from "./catalog-filters";

export type ViewMode = "list" | "grid";

const STATUS_OPTIONS: { value: ExerciseStatus; label: string }[] = [
  { value: "DRAFT", label: "Draft" },
  { value: "PUBLISHED", label: "Published" },
  { value: "ARCHIVED", label: "Archived" },
];

const MEDIA_STATUS_OPTIONS: { value: ExerciseMediaStatus; label: string }[] = [
  { value: "NONE", label: "No media" },
  { value: "PENDING", label: "Generating" },
  { value: "NEEDS_REVIEW", label: "Needs review" },
  { value: "APPROVED", label: "Approved" },
  { value: "FAILED", label: "Failed" },
];

const selectCls =
  "rounded-md border border-border-default bg-canvas px-2 py-1.5 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent";

interface Props {
  filters: CatalogFilters;
  onFiltersChange: (next: CatalogFilters) => void;
  sort: SortKey;
  onSortChange: (next: SortKey) => void;
  view: ViewMode;
  onViewChange: (next: ViewMode) => void;
  resultCount: number;
  onNewExercise: () => void;
}

export function CatalogToolbar({
  filters,
  onFiltersChange,
  sort,
  onSortChange,
  view,
  onViewChange,
  resultCount,
  onNewExercise,
}: Props) {
  function patch(p: Partial<CatalogFilters>) {
    onFiltersChange({ ...filters, ...p });
  }

  const filtersActive = hasActiveFilters(filters);

  return (
    <div className="mb-4 space-y-3">
      {/* Top row: search, sort, view toggle, new */}
      <div className="flex flex-wrap items-center gap-3">
        <input
          value={filters.search}
          onChange={(e) => patch({ search: e.target.value })}
          placeholder="Search by name, muscle, or pattern…"
          className="min-w-[200px] flex-1 rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />

        <label className="flex items-center gap-1.5 text-xs text-secondary">
          Sort
          <select
            value={sort}
            onChange={(e) => onSortChange(e.target.value as SortKey)}
            className={selectCls}
          >
            <option value="name">Name (A–Z)</option>
            <option value="imageCount">Image count</option>
          </select>
        </label>

        <ViewToggle view={view} onChange={onViewChange} />

        <button
          type="button"
          onClick={onNewExercise}
          className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90"
        >
          New exercise
        </button>
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-2 rounded-md border border-border-default bg-surface px-3 py-2">
        <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
          Filter
        </span>

        <FilterSelect
          label="Status"
          value={filters.status}
          onChange={(v) => patch({ status: v as ExerciseStatus | "any" })}
          options={STATUS_OPTIONS}
        />
        <FilterSelect
          label="Media"
          value={filters.mediaStatus}
          onChange={(v) => patch({ mediaStatus: v as ExerciseMediaStatus | "any" })}
          options={MEDIA_STATUS_OPTIONS}
        />
        <FilterSelect
          label="Plan"
          value={filters.planStatus}
          onChange={(v) => patch({ planStatus: v as ExerciseMediaStatus | "any" })}
          options={MEDIA_STATUS_OPTIONS}
        />

        <label className="flex items-center gap-1 text-xs text-secondary">
          Reviewed
          <select
            value={filters.reviewed}
            onChange={(e) => patch({ reviewed: e.target.value as ReviewedFilter })}
            className={selectCls}
          >
            <option value="any">Any</option>
            <option value="yes">Yes</option>
            <option value="no">No</option>
          </select>
        </label>

        <label className="flex items-center gap-1 text-xs text-secondary">
          Images
          <select
            value={filters.imageCount}
            onChange={(e) =>
              patch({ imageCount: e.target.value as ImageCountBucket })
            }
            className={selectCls}
          >
            <option value="any">Any</option>
            <option value="0">0</option>
            <option value="1">1</option>
            <option value="2">2</option>
            <option value="3+">3+</option>
          </select>
        </label>

        <FilterSelect
          label="Pattern"
          value={filters.movementPattern}
          onChange={(v) => patch({ movementPattern: v as MovementPattern | "any" })}
          options={MOVEMENT_PATTERNS.map((p) => ({
            value: p,
            label: MOVEMENT_PATTERN_LABEL[p],
          }))}
        />
        <FilterSelect
          label="Workout type"
          value={filters.blockType}
          onChange={(v) => patch({ blockType: v as BlockType | "any" })}
          options={BLOCK_TYPES.map((b) => ({
            value: b,
            label: BLOCK_TYPE_LABEL[b],
          }))}
        />

        {filtersActive ? (
          <button
            type="button"
            onClick={() => onFiltersChange({ ...EMPTY_FILTERS, search: filters.search })}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-2 py-1.5 text-xs font-medium text-primary hover:bg-surface"
          >
            Clear filters
          </button>
        ) : null}

        <span className="ml-auto text-xs text-tertiary">
          {resultCount} match{resultCount === 1 ? "" : "es"}
        </span>
      </div>
    </div>
  );
}

function FilterSelect<T extends string>({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: T | "any";
  onChange: (v: T | "any") => void;
  options: { value: T; label: string }[];
}) {
  return (
    <label className="flex items-center gap-1 text-xs text-secondary">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as T | "any")}
        className={selectCls}
      >
        <option value="any">Any</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

// Finder-style segmented control: list ⇄ grid.
function ViewToggle({
  view,
  onChange,
}: {
  view: ViewMode;
  onChange: (v: ViewMode) => void;
}) {
  return (
    <div
      className="inline-flex overflow-hidden rounded-md border border-border-default"
      role="group"
      aria-label="View mode"
    >
      <button
        type="button"
        onClick={() => onChange("list")}
        aria-pressed={view === "list"}
        aria-label="List view"
        className={
          "cursor-pointer px-2.5 py-2 text-sm " +
          (view === "list"
            ? "bg-accent text-inverse"
            : "bg-canvas text-secondary hover:bg-surface")
        }
      >
        <i className="ti ti-list" aria-hidden />
      </button>
      <button
        type="button"
        onClick={() => onChange("grid")}
        aria-pressed={view === "grid"}
        aria-label="Grid view"
        className={
          "cursor-pointer border-l border-border-default px-2.5 py-2 text-sm " +
          (view === "grid"
            ? "bg-accent text-inverse"
            : "bg-canvas text-secondary hover:bg-surface")
        }
      >
        <i className="ti ti-layout-grid" aria-hidden />
      </button>
    </div>
  );
}
