"use client";

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  useDroppable,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import type { AdminEquipment, SpecSchema, EquipmentSpecs } from '@/lib/types/gym';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { PendingEquipmentCard } from './PendingEquipmentCard';

interface Props {
  pending: AdminEquipment[];
  catalog: AdminEquipment[];
  approve: (equipmentId: string) => Promise<void>;
  reject: (equipmentId: string, reason: string) => Promise<void>;
  update: (
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) => Promise<void>;
  regenerate: (equipmentId: string, prompt: string) => Promise<void>;
  getImagePrompt: (equipmentId: string) => Promise<string>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
}

export function AdminEquipmentClient({
  pending,
  catalog,
  approve,
  reject,
  update,
  regenerate,
  getImagePrompt,
  merge,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }));
  const [activeId, setActiveId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const [catalogSearch, setCatalogSearch] = useState('');

  const activeEquipment = useMemo(
    () => pending.find(p => p.equipmentId === activeId) ?? null,
    [pending, activeId],
  );

  const filteredCatalog = useMemo(() => {
    if (!catalogSearch.trim()) return catalog;
    const q = catalogSearch.trim().toLowerCase();
    return catalog.filter(c => c.name.toLowerCase().includes(q));
  }, [catalog, catalogSearch]);

  function handleDragStart(event: DragStartEvent) {
    setActiveId(String(event.active.id));
  }

  async function handleDragEnd(event: DragEndEvent) {
    const sourceId = String(event.active.id);
    const overIdRaw = event.over ? String(event.over.id) : null;
    setActiveId(null);
    setOverId(null);
    if (!overIdRaw) return;
    const targetId = overIdRaw.replace(/^drop-/, '');
    if (targetId === sourceId) return;

    const sourceEq =
      pending.find(p => p.equipmentId === sourceId) ?? catalog.find(c => c.equipmentId === sourceId);
    const targetEq =
      pending.find(p => p.equipmentId === targetId) ?? catalog.find(c => c.equipmentId === targetId);
    if (!sourceEq || !targetEq) return;

    const ok = await confirm({
      title: 'Merge equipment',
      description: `Merge "${sourceEq.name}" into "${targetEq.name}"? The source will be marked as a duplicate of the target. Any gyms using the source will switch to the target.`,
      confirmLabel: 'Merge',
      tone: 'danger',
    });
    if (!ok) return;

    try {
      await merge(sourceId, targetId);
      toast.success('Merged', { description: `"${sourceEq.name}" is now an alias of "${targetEq.name}"` });
      router.refresh();
    } catch (e) {
      toast.error('Merge failed', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    }
  }

  return (
    <DndContext
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragOver={(e) => setOverId(e.over ? String(e.over.id) : null)}
      onDragEnd={handleDragEnd}
      onDragCancel={() => {
        setActiveId(null);
        setOverId(null);
      }}
    >
      <div className="grid grid-cols-[1fr_320px] gap-6">
        <div className="space-y-4">
          {pending.length === 0 ? (
            <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
              <p className="text-sm text-secondary">No equipment pending review</p>
            </div>
          ) : (
            pending.map(eq => (
              <PendingEquipmentCard
                key={eq.equipmentId}
                equipment={eq}
                approve={async (id) => { await approve(id); router.refresh(); }}
                reject={async (id, reason) => { await reject(id, reason); router.refresh(); }}
                update={async (id, data) => { await update(id, data); router.refresh(); }}
                regenerate={async (id, prompt) => { await regenerate(id, prompt); router.refresh(); }}
                getImagePrompt={getImagePrompt}
                isDragOver={overId === `drop-${eq.equipmentId}` && activeId !== eq.equipmentId}
              />
            ))
          )}
        </div>

        <CatalogPanel
          items={filteredCatalog}
          search={catalogSearch}
          onSearchChange={setCatalogSearch}
          activeDragId={activeId}
          overId={overId}
        />
      </div>

      <DragOverlay>
        {activeEquipment ? (
          <div className="rounded-lg border-2 border-accent bg-surface p-4 shadow-2xl opacity-95">
            <p className="text-sm font-semibold text-primary">{activeEquipment.name}</p>
            <p className="text-xs text-secondary">
              {activeEquipment.category} · {activeEquipment.subcategory}
            </p>
            <p className="mt-1 text-[10px] uppercase tracking-wider text-accent">
              Drop on another card to merge
            </p>
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}

function CatalogPanel({
  items,
  search,
  onSearchChange,
  activeDragId,
  overId,
}: {
  items: AdminEquipment[];
  search: string;
  onSearchChange: (q: string) => void;
  activeDragId: string | null;
  overId: string | null;
}) {
  return (
    <aside className="rounded-lg border border-border-default bg-surface p-4">
      <div className="mb-3">
        <h2 className="text-sm font-semibold text-primary">Active catalog</h2>
        <p className="text-xs text-tertiary">
          Drag a pending card onto a row here to merge it into the catalog item.
        </p>
      </div>
      <input
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
        placeholder="Search catalog…"
        className="mb-3 w-full rounded-md border border-border-default bg-canvas px-2.5 py-1.5 text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent"
      />
      <div className="space-y-1.5 max-h-[70vh] overflow-y-auto pr-1">
        {items.length === 0 ? (
          <p className="text-xs text-tertiary">No catalog items match.</p>
        ) : (
          items.map(c => (
            <CatalogDropRow
              key={c.equipmentId}
              equipment={c}
              isDragging={activeDragId === c.equipmentId}
              isOver={overId === `drop-${c.equipmentId}` && activeDragId !== c.equipmentId}
            />
          ))
        )}
      </div>
    </aside>
  );
}

function CatalogDropRow({
  equipment,
  isDragging,
  isOver,
}: {
  equipment: AdminEquipment;
  isDragging: boolean;
  isOver: boolean;
}) {
  const droppable = useDroppable({ id: `drop-${equipment.equipmentId}`, data: { equipment } });
  const classes =
    'flex items-center gap-2 rounded-md border px-2 py-1.5 text-xs ' +
    (isOver
      ? 'border-accent bg-accent-bg'
      : isDragging
        ? 'border-border-default opacity-40'
        : 'border-border-default bg-canvas');
  return (
    <div ref={droppable.setNodeRef} className={classes}>
      {equipment.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={equipment.imageUrl}
          alt=""
          className="h-8 w-8 shrink-0 rounded border border-border-default object-cover"
        />
      ) : (
        <div className="h-8 w-8 shrink-0 rounded border border-dashed border-border-default" />
      )}
      <div className="min-w-0 flex-1">
        <p className="truncate text-primary">{equipment.name}</p>
        <p className="truncate text-[10px] text-tertiary">{equipment.subcategory}</p>
      </div>
    </div>
  );
}
