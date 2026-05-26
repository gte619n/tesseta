"use client";

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import type { Drug, DrugCategory, DrugForm } from '@/lib/types/medication';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { DrugAdminCard } from './DrugAdminCard';

interface Props {
  drugs: Drug[];
  update: (
    drugId: string,
    data: { name: string; aliases: string[]; category: DrugCategory; form: DrugForm; defaultUnit: string },
  ) => Promise<void>;
  regenerate: (drugId: string, prompt: string) => Promise<void>;
  getImagePrompt: (drugId: string) => Promise<string>;
  merge: (sourceId: string, targetId: string) => Promise<void>;
  remove: (drugId: string) => Promise<void>;
}

export function AdminDrugClient({
  drugs,
  update,
  regenerate,
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
      sensors={sensors}
      onDragStart={handleDragStart}
      onDragOver={(e) => setOverId(e.over ? String(e.over.id) : null)}
      onDragEnd={handleDragEnd}
      onDragCancel={() => {
        setActiveId(null);
        setOverId(null);
      }}
    >
      <div className="mb-4">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name or alias…"
          className="w-full max-w-md rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />
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

      <DragOverlay>
        {activeDrug ? (
          <div className="rounded-lg border-2 border-accent bg-surface p-4 shadow-2xl opacity-95">
            <p className="text-sm font-semibold text-primary">{activeDrug.name}</p>
            {activeDrug.aliases.length > 0 ? (
              <p className="text-xs text-secondary">{activeDrug.aliases.join(', ')}</p>
            ) : null}
            <p className="mt-1 text-[10px] uppercase tracking-wider text-accent">
              Drop on another drug to merge
            </p>
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
