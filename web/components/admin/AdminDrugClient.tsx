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

import type { Drug, DrugCategory, DrugForm } from '@/lib/types/medication';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { DrugAdminCard } from './DrugAdminCard';
import { AddDrugModal } from './AddDrugModal';

interface Props {
  drugs: Drug[];
  create: (data: {
    name: string;
    aliases: string[];
    category: DrugCategory;
    form: DrugForm;
    defaultUnit: string;
    commonDoses: string[];
    suggestedMarkers: string[];
    description: string | null;
  }) => Promise<void>;
  update: (
    drugId: string,
    data: { name: string; aliases: string[]; category: DrugCategory; form: DrugForm; defaultUnit: string },
  ) => Promise<void>;
  regenerate: (drugId: string, prompt: string) => Promise<void>;
  uploadImage: (drugId: string, file: File) => Promise<void>;
  selectImage: (drugId: string, imageUrl: string) => Promise<void>;
  deleteImage: (drugId: string, imageUrl: string) => Promise<void>;
  getImagePrompt: (drugId: string) => Promise<string>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
  remove: (drugId: string) => Promise<void>;
}

export function AdminDrugClient({
  drugs,
  create,
  update,
  regenerate,
  uploadImage,
  selectImage,
  deleteImage,
  getImagePrompt,
  merge,
  remove,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }));
  const [activeId, setActiveId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showAdd, setShowAdd] = useState(false);
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
  }, []);

  const filtered = useMemo(() => {
    if (!search.trim()) return drugs;
    const q = search.trim().toLowerCase();
    return drugs.filter(
      d =>
        d.name.toLowerCase().includes(q) ||
        d.aliases.some(a => a.toLowerCase().includes(q)),
    );
  }, [drugs, search]);

  const activeDrug = useMemo(
    () => drugs.find(d => d.drugId === activeId) ?? null,
    [drugs, activeId],
  );

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

    const source = drugs.find(d => d.drugId === sourceId);
    const target = drugs.find(d => d.drugId === targetId);
    if (!source || !target) return;

    const ok = await confirm({
      title: 'Merge drugs',
      description: `Merge "${source.name}" into "${target.name}"? The source will be marked as a duplicate. Any user medications referencing the source will switch to the target.`,
      confirmLabel: 'Merge',
      tone: 'danger',
    });
    if (!ok) return;

    try {
      await merge(sourceId, targetId);
      toast.success('Merged', {
        description: `"${source.name}" is now an alias of "${target.name}"`,
      });
      router.refresh();
    } catch (e) {
      toast.error('Merge failed', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    }
  }

  return (
    <DndContext
      id="admin-drug-dnd"
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragOver={(e) => setOverId(e.over ? String(e.over.id) : null)}
      onDragEnd={handleDragEnd}
      onDragCancel={() => {
        setActiveId(null);
        setOverId(null);
      }}
    >
      <div className="mb-4 flex items-center gap-3">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name or alias…"
          className="w-full max-w-md rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />
        <button
          type="button"
          onClick={() => setShowAdd(true)}
          className="ml-auto shrink-0 cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90"
        >
          Add drug
        </button>
      </div>

      <div className="space-y-4">
        {filtered.length === 0 ? (
          <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
            <p className="text-sm text-secondary">
              {drugs.length === 0
                ? 'No drugs in catalog yet'
                : 'No drugs match this search'}
            </p>
          </div>
        ) : (
          filtered.map(d => (
            <DrugAdminCard
              key={d.drugId}
              drug={d}
              update={async (id, data) => {
                await update(id, data);
                router.refresh();
              }}
              regenerate={async (id, prompt) => {
                await regenerate(id, prompt);
                router.refresh();
              }}
              uploadImage={async (id, file) => {
                await uploadImage(id, file);
                router.refresh();
              }}
              selectImage={async (id, imageUrl) => {
                await selectImage(id, imageUrl);
                router.refresh();
              }}
              deleteImage={async (id, imageUrl) => {
                await deleteImage(id, imageUrl);
                router.refresh();
              }}
              getImagePrompt={getImagePrompt}
              remove={async (id) => {
                await remove(id);
                router.refresh();
              }}
              isDragOver={overId === `drop-${d.drugId}` && activeId !== d.drugId}
            />
          ))
        )}
      </div>

      {mounted &&
        createPortal(
          <DragOverlay dropAnimation={null} modifiers={[snapCenterToCursor]}>
            {activeDrug ? <DragPreview drug={activeDrug} /> : null}
          </DragOverlay>,
          document.body,
        )}

      <AddDrugModal
        isOpen={showAdd}
        onClose={() => setShowAdd(false)}
        onSave={() => {
          setShowAdd(false);
          router.refresh();
        }}
        create={create}
      />
    </DndContext>
  );
}

function DragPreview({ drug }: { drug: Drug }) {
  const imageSrc = drug.imageUrl || drug.imageFallback;
  return (
    <div className="inline-flex max-w-xs items-center gap-2 rounded-full border border-border-default bg-surface px-3 py-1.5 shadow-md">
      {imageSrc ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={imageSrc}
          alt=""
          className="h-6 w-6 shrink-0 rounded-full border border-border-default object-cover"
        />
      ) : (
        <div className="h-6 w-6 shrink-0 rounded-full border border-dashed border-border-default" />
      )}
      <span className="truncate text-xs font-medium text-primary">{drug.name}</span>
    </div>
  );
}

