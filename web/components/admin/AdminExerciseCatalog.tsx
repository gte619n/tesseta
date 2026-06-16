"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { EditExerciseModal } from "./EditExerciseModal";
import { CatalogToolbar, type ViewMode } from "./CatalogToolbar";
import { ExerciseListView } from "./ExerciseListView";
import { ExerciseGridView } from "./ExerciseGridView";
import { ExerciseDetailDrawer, type DrawerActions } from "./ExerciseDetailDrawer";
import {
  EMPTY_FILTERS,
  applyFilters,
  sortCatalog,
  type CatalogFilters,
  type CatalogPreset,
  type SortKey,
} from "./catalog-filters";
import type {
  ExerciseResponse,
  ExerciseSummaryResponse,
  ExerciseEditableFields,
} from "@/lib/types/exercise";
import type { Equipment } from "@/lib/types/gym";

const VIEW_MODE_KEY = "exerciseAdminViewMode";

interface Props extends DrawerActions {
  summary: ExerciseSummaryResponse[];
  equipmentNames: Record<string, string>;
  // IMPL-20: "needs-review" applies the folded Review-tab preset.
  preset: CatalogPreset;
  save: (data: ExerciseEditableFields, exerciseId: string | null) => Promise<void>;
  searchEquipment: (search: string) => Promise<Equipment[]>;
}

export function AdminExerciseCatalog({
  summary,
  equipmentNames,
  preset,
  save,
  searchEquipment,
  ...drawerActions
}: Props) {
  const router = useRouter();

  const [filters, setFilters] = useState<CatalogFilters>(EMPTY_FILTERS);
  const [sort, setSort] = useState<SortKey>("name");
  // SSR-safe default; the real persisted choice is loaded in an effect below.
  const [view, setView] = useState<ViewMode>("grid");

  // Optimistic reviewed overrides keyed by exerciseId — kept in sync with the
  // detail drawer + grid/list checkboxes so a toggle reflects instantly without
  // a full refetch of the summary list.
  const [reviewedOverrides, setReviewedOverrides] = useState<
    Record<string, boolean>
  >({});

  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  // The full detail being edited (loaded by the drawer, lifted here for the
  // EditExerciseModal which needs the full ExerciseResponse).
  const [editing, setEditing] = useState<ExerciseResponse | null>(null);

  // Load persisted view mode (client-only — guards SSR).
  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(VIEW_MODE_KEY);
      if (stored === "list" || stored === "grid") setView(stored);
    } catch {
      // localStorage unavailable (private mode / SSR) — keep the default.
    }
  }, []);

  function handleViewChange(next: ViewMode) {
    setView(next);
    try {
      window.localStorage.setItem(VIEW_MODE_KEY, next);
    } catch {
      // ignore persistence failure
    }
  }

  function reviewedFor(ex: ExerciseSummaryResponse): boolean {
    return reviewedOverrides[ex.exerciseId] ?? ex.reviewed;
  }

  async function handleToggleReviewed(exerciseId: string, reviewed: boolean) {
    setReviewedOverrides((cur) => ({ ...cur, [exerciseId]: reviewed }));
    try {
      await drawerActions.setReviewed(exerciseId, reviewed);
    } catch {
      setReviewedOverrides((cur) => ({ ...cur, [exerciseId]: !reviewed }));
    }
  }

  const visible = useMemo(() => {
    const filtered = applyFilters(summary, filters, preset);
    return sortCatalog(filtered, sort);
  }, [summary, filters, preset, sort]);

  return (
    <>
      <CatalogToolbar
        filters={filters}
        onFiltersChange={setFilters}
        sort={sort}
        onSortChange={setSort}
        view={view}
        onViewChange={handleViewChange}
        resultCount={visible.length}
        onNewExercise={() => setCreating(true)}
      />

      {visible.length === 0 ? (
        <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
          <p className="text-sm text-secondary">No exercises match.</p>
        </div>
      ) : view === "list" ? (
        <ExerciseListView
          items={visible}
          reviewedFor={reviewedFor}
          onToggleReviewed={handleToggleReviewed}
          onOpen={setOpenId}
        />
      ) : (
        <ExerciseGridView
          items={visible}
          reviewedFor={reviewedFor}
          onToggleReviewed={handleToggleReviewed}
          onOpen={setOpenId}
        />
      )}

      <ExerciseDetailDrawer
        {...drawerActions}
        exerciseId={openId}
        summary={summary}
        onClose={() => setOpenId(null)}
        onEdit={(ex) => setEditing(ex)}
        onReviewedChange={(id, reviewed) =>
          setReviewedOverrides((cur) => ({ ...cur, [id]: reviewed }))
        }
      />

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
