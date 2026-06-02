"use client";

import { useState } from "react";
import { useDraggable, useDroppable } from "@dnd-kit/core";
import type {
  MealGroup,
  Entry,
  Meal,
  Macros,
  UpdateEntryBody,
  UpdateIngredientBody,
} from "@/lib/types/nutrition";
import { MEAL_LABELS, MEAL_ICONS } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { AddFoodModal } from "@/components/nutrition/AddFoodModal";
import { EditEntryModal } from "@/components/nutrition/EditEntryModal";
import { IngredientsModal } from "@/components/nutrition/IngredientsModal";
import { FoodImage } from "@/components/nutrition/FoodImage";

type Props = {
  group: MealGroup;
  date: string;
  addEntry: (
    date: string,
    body: {
      meal: Meal;
      foodId: string | null;
      foodName: string;
      servingLabel: string;
      servingGrams: number;
      quantity: number;
      macros: Macros;
      source: "MANUAL" | "CATALOG";
    },
  ) => Promise<void>;
  updateEntry: (
    date: string,
    entryId: string,
    body: UpdateEntryBody,
  ) => Promise<void>;
  updateIngredient: (
    date: string,
    entryId: string,
    index: number,
    body: UpdateIngredientBody,
  ) => Promise<void>;
  deleteEntry: (date: string, entryId: string) => Promise<void>;
  searchFoods: (q: string) => Promise<
    {
      foodId: string;
      name: string;
      brand: string | null;
      macrosPer100g: Macros;
      servingSizes: { label: string; grams: number }[];
      defaultServingIndex: number;
      source: string;
    }[]
  >;
  // The entryId currently being dragged (lifted by the DnD context), so this
  // section can suppress its drop highlight when the entry already lives here.
  activeId: string | null;
};

export function MealSection({
  group,
  date,
  addEntry,
  updateEntry,
  updateIngredient,
  deleteEntry,
  searchFoods,
  activeId,
}: Props) {
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Entry | null>(null);
  const [editingComposite, setEditingComposite] = useState<Entry | null>(null);
  const toast = useToast();
  const confirm = useConfirm();

  // A composite (photo-logged) meal opens the ingredients modal; everything
  // else opens the single-food edit modal.
  function openEntry(entry: Entry) {
    if (entry.ingredients && entry.ingredients.length > 0) {
      setEditingComposite(entry);
    } else {
      setEditing(entry);
    }
  }

  async function handleDelete(entry: Entry) {
    const ok = await confirm({
      title: "Remove food entry?",
      description: `Remove "${entry.foodName}" from ${MEAL_LABELS[group.meal]}?`,
      confirmLabel: "Remove",
      tone: "danger",
    });
    if (!ok) return;
    try {
      await deleteEntry(date, entry.entryId);
      toast.success("Entry removed");
    } catch {
      toast.error("Failed to remove entry");
    }
  }

  const sub = group.subtotal;
  const hasEntries = group.entries.length > 0;

  // Each meal section is a drop target keyed by its meal. Highlight only when an
  // entry from *another* meal is hovering over it (dropping in place is a no-op).
  const { setNodeRef, isOver } = useDroppable({ id: group.meal });
  const isForeignDragOver =
    isOver &&
    activeId !== null &&
    !group.entries.some((e) => e.entryId === activeId);

  return (
    <div
      ref={setNodeRef}
      className={`rounded-[12px] border-[0.5px] bg-surface transition-colors ${
        isForeignDragOver
          ? "border-accent ring-2 ring-accent/40"
          : "border-border-default"
      }`}
    >
      {/* Meal header */}
      <div className="flex items-center justify-between border-b-[0.5px] border-border-subtle px-5 py-3">
        <div className="flex items-center gap-2.5">
          <i
            className={`ti ti-${MEAL_ICONS[group.meal]} text-[15px] text-tertiary`}
            aria-hidden
          />
          <h3 className="m-0 text-[14px] font-medium text-primary">
            {MEAL_LABELS[group.meal]}
          </h3>
          {hasEntries && (
            <span className="caps-mono rounded-[4px] bg-canvas-sunken px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
              {Math.round(sub.caloriesKcal ?? 0)} kcal
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={() => setModalOpen(true)}
          className="caps-mono inline-flex items-center gap-1.5 rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-1 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
        >
          <i className="ti ti-plus text-[11px]" aria-hidden />
          Add food
        </button>
      </div>

      {/* Entries */}
      {hasEntries ? (
        <div className="divide-y divide-border-subtle">
          {group.entries.map((entry) => (
            <EntryRow
              key={entry.entryId}
              entry={entry}
              onEdit={() => openEntry(entry)}
              onDelete={() => handleDelete(entry)}
            />
          ))}
        </div>
      ) : (
        <div className="px-5 py-6 text-center text-[13px] text-tertiary">
          No foods logged yet. Add a food to get started.
        </div>
      )}

      {/* Meal subtotal */}
      {hasEntries && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t-[0.5px] border-border-subtle px-5 py-2.5">
          <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
            Subtotal
          </span>
          <MacroChip label="P" value={sub.proteinGrams} unit="g" />
          <MacroChip label="C" value={sub.carbsGrams} unit="g" />
          <MacroChip label="F" value={sub.fatGrams} unit="g" />
          <MacroChip label="Fiber" value={sub.fiberGrams} unit="g" />
        </div>
      )}

      <AddFoodModal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        meal={group.meal}
        date={date}
        addEntry={addEntry}
        searchFoods={searchFoods}
      />

      {editing && (
        <EditEntryModal
          isOpen={editing !== null}
          onClose={() => setEditing(null)}
          entry={editing}
          date={date}
          updateEntry={updateEntry}
        />
      )}

      {editingComposite && (
        <IngredientsModal
          isOpen={editingComposite !== null}
          onClose={() => setEditingComposite(null)}
          entry={editingComposite}
          date={date}
          updateEntry={updateEntry}
          updateIngredient={updateIngredient}
        />
      )}
    </div>
  );
}

function EntryRow({
  entry,
  onEdit,
  onDelete,
}: {
  entry: Entry;
  onEdit: () => void;
  onDelete: () => Promise<void>;
}) {
  // Drag handle moves the entry between meals. Only the grip carries the drag
  // listeners so the row's edit / delete clicks keep working.
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({
    id: entry.entryId,
  });
  return (
    <div
      ref={setNodeRef}
      className={`group flex items-center gap-3 px-5 py-3 hover:bg-canvas-sunken/30 ${
        isDragging ? "opacity-40" : ""
      }`}
    >
      {/* Drag handle: hold and drag to move this food to another meal */}
      <button
        type="button"
        {...listeners}
        {...attributes}
        className="-ml-1 shrink-0 cursor-grab touch-none rounded p-1 text-tertiary opacity-60 hover:text-secondary active:cursor-grabbing group-hover:opacity-100"
        aria-label={`Move ${entry.foodName} to another meal`}
      >
        <i className="ti ti-grip-vertical text-[14px]" aria-hidden />
      </button>
      {/* Click the food (image + name) to edit serving / macros */}
      <button
        type="button"
        onClick={onEdit}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
        aria-label={`Edit ${entry.foodName}`}
      >
        <FoodImage
          imageUrl={entry.imageUrl}
          imageStatus={entry.imageStatus}
          size={40}
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[13px] font-medium text-primary group-hover:text-accent-dim">
              {entry.foodName}
            </span>
            {entry.source === "MANUAL" && (
              <span className="caps-mono shrink-0 rounded-[3px] bg-canvas-sunken px-1 py-px text-[8px] tracking-[0.06em] text-tertiary">
                quick add
              </span>
            )}
          </div>
          <div className="mt-0.5 caps-mono text-[9px] tracking-[0.04em] text-tertiary">
            {entry.servingLabel} × {entry.quantity}
          </div>
        </div>
      </button>
      <div className="flex shrink-0 items-center gap-3">
        <div className="hidden items-center gap-3 sm:flex">
          <MacroChip label="P" value={entry.macros.proteinGrams} unit="g" />
          <MacroChip label="C" value={entry.macros.carbsGrams} unit="g" />
          <MacroChip label="F" value={entry.macros.fatGrams} unit="g" />
        </div>
        <span className="font-mono text-[13px] font-medium tabular-nums text-primary">
          {Math.round(entry.macros.caloriesKcal ?? 0)}
          <span className="ml-0.5 text-[10px] font-normal text-tertiary">
            kcal
          </span>
        </span>
        <button
          type="button"
          onClick={onEdit}
          className="cursor-pointer rounded p-1 text-tertiary opacity-0 hover:text-accent-dim group-hover:opacity-100"
          aria-label={`Edit ${entry.foodName}`}
        >
          <i className="ti ti-pencil text-[13px]" aria-hidden />
        </button>
        <button
          type="button"
          onClick={onDelete}
          className="cursor-pointer rounded p-1 text-tertiary opacity-0 hover:text-alert group-hover:opacity-100"
          aria-label={`Remove ${entry.foodName}`}
        >
          <i className="ti ti-trash text-[13px]" aria-hidden />
        </button>
      </div>
    </div>
  );
}

function MacroChip({
  label,
  value,
  unit,
}: {
  label: string;
  value: number | null;
  unit: string;
}) {
  return (
    <span className="caps-mono text-[9px] tracking-[0.04em] text-tertiary">
      {label}{" "}
      <span className="font-mono text-[10px] font-medium text-secondary tabular-nums">
        {Math.round(value ?? 0)}
      </span>
      {unit}
    </span>
  );
}
