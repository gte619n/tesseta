"use client";

import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
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
  type Modifier,
} from '@dnd-kit/core';

// Pull the cursor (x, y) out of the activator event, regardless of whether it
// originated from a pointer, mouse, or touch interaction. Avoids depending on
// `@dnd-kit/utilities`, which isn't a direct dep of this package.
function getEventCoordinates(event: Event): { x: number; y: number } | null {
  if ('touches' in event) {
    const touchEvent = event as TouchEvent;
    const touch = touchEvent.touches[0] ?? touchEvent.changedTouches[0];
    if (!touch) return null;
    return { x: touch.clientX, y: touch.clientY };
  }
  if ('clientX' in event && 'clientY' in event) {
    const mouseEvent = event as MouseEvent;
    return { x: mouseEvent.clientX, y: mouseEvent.clientY };
  }
  return null;
}

// Snap the drag overlay so the cursor sits at the center of the overlay node
// instead of at the source element's top-left (dnd-kit's default).
const snapCenterToCursor: Modifier = ({ activatorEvent, draggingNodeRect, transform }) => {
  if (!draggingNodeRect || !activatorEvent) return transform;
  const activatorCoordinates = getEventCoordinates(activatorEvent);
  if (!activatorCoordinates) return transform;
  const offsetX = activatorCoordinates.x - draggingNodeRect.left;
  const offsetY = activatorCoordinates.y - draggingNodeRect.top;
  return {
    ...transform,
    x: transform.x + offsetX - draggingNodeRect.width / 2,
    y: transform.y + offsetY - draggingNodeRect.height / 2,
  };
};
import type { AdminEquipment, SpecSchema, EquipmentSpecs } from '@/lib/types/gym';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { ImageLightbox } from './ImageLightbox';
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
  uploadImage: (equipmentId: string, file: File) => Promise<void>;
  getImageStatus: (equipmentId: string) => Promise<string | null>;
  getImagePrompt: (equipmentId: string) => Promise<string>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
  selectImage: (equipmentId: string, imageUrl: string) => Promise<void>;
  deleteImage: (equipmentId: string, imageUrl: string) => Promise<void>;
}

export function AdminEquipmentClient({
  pending,
  catalog,
  approve,
  reject,
  update,
  regenerate,
  uploadImage,
  getImageStatus,
  getImagePrompt,
  merge,
  selectImage,
  deleteImage,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }));
  const [activeId, setActiveId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const [catalogSearch, setCatalogSearch] = useState('');
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
  }, []);

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
      id="admin-equipment-dnd"
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragOver={(e) => setOverId(e.over ? String(e.over.id) : null)}
      onDragEnd={handleDragEnd}
      onDragCancel={() => {
        setActiveId(null);
        setOverId(null);
      }}
    >
      <div className="grid grid-cols-[1fr_320px] items-start gap-6">
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
                regenerate={async (id, prompt) => {
                  await regenerate(id, prompt);
                  // Modal awaits this promise before closing — resolve as
                  // soon as the kick-off API returns, then poll in the
                  // background. Image generation is async on the backend
                  // (~15-20s); poll every 3s for up to 60s, refreshing the
                  // page when imageStatus leaves PENDING (i.e. GENERATED
                  // or FAILED). If we time out, refresh once anyway so the
                  // admin can manually retry.
                  void (async () => {
                    for (let i = 0; i < 20; i += 1) {
                      await new Promise(r => setTimeout(r, 3000));
                      try {
                        const status = await getImageStatus(id);
                        if (status && status !== 'PENDING' && status !== 'GENERATING') {
                          router.refresh();
                          return;
                        }
                      } catch {
                        // swallow transient network errors and keep polling
                      }
                    }
                    router.refresh();
                  })();
                }}
                uploadImage={async (id, file) => { await uploadImage(id, file); router.refresh(); }}
                getImagePrompt={getImagePrompt}
                selectImage={async (id, url) => { await selectImage(id, url); router.refresh(); }}
                deleteImage={async (id, url) => { await deleteImage(id, url); router.refresh(); }}
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
          onZoom={(src) => setLightboxSrc(src)}
        />
      </div>

      <ImageLightbox
        src={lightboxSrc}
        alt=""
        onClose={() => setLightboxSrc(null)}
      />

      {mounted &&
        createPortal(
          <DragOverlay dropAnimation={null} modifiers={[snapCenterToCursor]}>
            {activeEquipment ? <DragPreview equipment={activeEquipment} /> : null}
          </DragOverlay>,
          document.body,
        )}
    </DndContext>
  );
}

function CatalogPanel({
  items,
  search,
  onSearchChange,
  activeDragId,
  overId,
  onZoom,
}: {
  items: AdminEquipment[];
  search: string;
  onSearchChange: (q: string) => void;
  activeDragId: string | null;
  overId: string | null;
  onZoom: (src: string) => void;
}) {
  return (
    <aside className="sticky top-4 flex max-h-[calc(100vh-2rem)] flex-col rounded-lg border border-border-default bg-surface p-4">
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
      <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto pr-1">
        {items.length === 0 ? (
          <p className="text-xs text-tertiary">No catalog items match.</p>
        ) : (
          items.map(c => (
            <CatalogDropRow
              key={c.equipmentId}
              equipment={c}
              isDragging={activeDragId === c.equipmentId}
              isOver={overId === `drop-${c.equipmentId}` && activeDragId !== c.equipmentId}
              onZoom={onZoom}
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
  onZoom,
}: {
  equipment: AdminEquipment;
  isDragging: boolean;
  isOver: boolean;
  onZoom: (src: string) => void;
}) {
  const droppable = useDroppable({ id: `drop-${equipment.equipmentId}`, data: { equipment } });
  const classes =
    'flex items-center gap-2 rounded-md border px-2 py-1.5 text-xs ' +
    (isOver
      ? 'border-accent bg-accent-bg'
      : isDragging
        ? 'border-border-default opacity-40'
        : 'border-border-default bg-canvas');
  const isZoomable = !!equipment.imageUrl && equipment.imageStatus === 'GENERATED';
  return (
    <div ref={droppable.setNodeRef} className={classes}>
      {equipment.imageUrl ? (
        isZoomable ? (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              if (equipment.imageUrl) onZoom(equipment.imageUrl);
            }}
            className="block h-8 w-8 shrink-0 cursor-zoom-in rounded border border-border-default p-0"
            aria-label={`Zoom image for ${equipment.name}`}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={equipment.imageUrl}
              alt={equipment.name}
              className="h-full w-full rounded object-cover"
            />
          </button>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={equipment.imageUrl}
            alt=""
            className="h-8 w-8 shrink-0 rounded border border-border-default object-cover"
          />
        )
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

function DragPreview({ equipment }: { equipment: AdminEquipment }) {
  return (
    <div className="inline-flex max-w-xs items-center gap-2 rounded-full border border-border-default bg-surface px-3 py-1.5 shadow-md">
      {equipment.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={equipment.imageUrl}
          alt=""
          className="h-6 w-6 shrink-0 rounded-full border border-border-default object-cover"
        />
      ) : (
        <div className="h-6 w-6 shrink-0 rounded-full border border-dashed border-border-default" />
      )}
      <span className="truncate text-xs font-medium text-primary">{equipment.name}</span>
    </div>
  );
}
