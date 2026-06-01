"use client";

import { useRef, useState } from 'react';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import type { AdminEquipment, SpecSchema, EquipmentSpecs } from '@/lib/types/gym';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { EditEquipmentModal } from './EditEquipmentModal';
import { ImageLightbox } from './ImageLightbox';
import { ImageCandidateStrip } from './ImageCandidateStrip';
import { RegenerateImageModal } from './RegenerateImageModal';

interface PendingEquipmentCardProps {
  equipment: AdminEquipment;
  approve: (equipmentId: string) => Promise<void>;
  reject: (equipmentId: string, reason: string) => Promise<void>;
  update: (
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) => Promise<void>;
  regenerate: (equipmentId: string, prompt: string) => Promise<void>;
  uploadImage: (equipmentId: string, file: File) => Promise<void>;
  getImagePrompt: (equipmentId: string) => Promise<string>;
  selectImage: (equipmentId: string, imageUrl: string) => Promise<void>;
  deleteImage: (equipmentId: string, imageUrl: string) => Promise<void>;
  isDragOver?: boolean;
}

export function PendingEquipmentCard({
  equipment,
  approve,
  reject,
  update,
  regenerate,
  uploadImage,
  getImagePrompt,
  selectImage,
  deleteImage,
  isDragOver,
}: PendingEquipmentCardProps) {
  const confirm = useConfirm();
  const toast = useToast();
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isRegenOpen, setIsRegenOpen] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleUploadFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset the input value so re-selecting the same file fires onChange.
    e.target.value = '';
    if (!file) return;
    setIsProcessing(true);
    try {
      await uploadImage(equipment.equipmentId, file);
      toast.success('Image uploaded');
    } catch (err) {
      toast.error('Upload failed', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
    }
  }

  const draggable = useDraggable({ id: equipment.equipmentId, data: { equipment } });
  const droppable = useDroppable({ id: `drop-${equipment.equipmentId}`, data: { equipment } });

  async function handleApprove() {
    const ok = await confirm({
      title: 'Approve equipment',
      description: `Add "${equipment.name}" to the catalog and generate an image.`,
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    setIsProcessing(true);
    try {
      await approve(equipment.equipmentId);
      toast.success('Equipment approved');
    } catch (e) {
      toast.error('Failed to approve', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleReject() {
    const ok = await confirm({
      title: 'Reject equipment',
      description: `Are you sure you want to reject "${equipment.name}"?`,
      confirmLabel: 'Reject',
      tone: 'danger',
    });
    if (!ok) return;
    setIsProcessing(true);
    try {
      await reject(equipment.equipmentId, '');
      toast.success('Equipment rejected');
    } catch (e) {
      toast.error('Failed to reject', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
    }
  }

  const isOver = isDragOver ?? droppable.isOver;
  const isDragging = draggable.isDragging;
  const baseClasses =
    'rounded-lg border bg-surface p-5 transition-shadow ' +
    (isOver
      ? 'border-accent ring-2 ring-accent/50 shadow-lg'
      : 'border-border-default') +
    (isDragging ? ' opacity-30' : '');

  return (
    <>
      <div ref={droppable.setNodeRef} className={baseClasses}>
        <div
          ref={draggable.setNodeRef}
          className="flex gap-5"
        >
          <div className="flex shrink-0 flex-col gap-2">
            <ImageThumb
              url={equipment.imageUrl}
              status={equipment.imageStatus}
              onZoom={(src) => setLightboxSrc(src)}
              alt={equipment.name}
            />
            <ImageCandidateStrip
              id={equipment.equipmentId}
              name={equipment.name}
              activeUrl={equipment.imageUrl}
              candidates={equipment.imageCandidates ?? []}
              selectImage={selectImage}
              deleteImage={deleteImage}
            />
          </div>

          <div className="flex-1 min-w-0">
            <div
              {...draggable.listeners}
              {...draggable.attributes}
              className="cursor-grab active:cursor-grabbing"
            >
              <div className="flex items-center gap-2">
                <h3 className="truncate text-base font-semibold text-primary">
                  {equipment.name}
                </h3>
                <SpecSchemaChip schema={equipment.specSchema} />
              </div>
              <p className="mt-0.5 text-xs text-secondary">
                {equipment.category} · {equipment.subcategory}
              </p>
              <p className="mt-2 text-xs text-tertiary">
                Submitted by{' '}
                <span className="font-medium text-secondary">
                  {equipment.contributorDisplayName ?? equipment.contributorEmail ?? equipment.contributorId ?? 'Unknown'}
                </span>
                {equipment.contributorEmail && equipment.contributorDisplayName ? (
                  <span className="text-quaternary"> · {equipment.contributorEmail}</span>
                ) : null}
                <span className="text-quaternary"> · {formatDate(equipment.submittedAt)}</span>
              </p>
              <p className="mt-2 text-xs text-secondary">
                {formatSpecs(equipment.specSchema, equipment.specs)}
              </p>
            </div>

            <div className="mt-4 flex flex-wrap items-center gap-2">
              <button
                onClick={handleApprove}
                disabled={isProcessing}
                className="cursor-pointer rounded-md bg-green-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Approve
              </button>
              <button
                onClick={() => setIsEditOpen(true)}
                disabled={isProcessing}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
              >
                Edit
              </button>
              <button
                onClick={() => setIsRegenOpen(true)}
                disabled={isProcessing}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
              >
                Regenerate image
              </button>
              <button
                onClick={() => fileInputRef.current?.click()}
                disabled={isProcessing}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-xs font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
              >
                Upload photo
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={handleUploadFile}
              />
              <button
                onClick={handleReject}
                disabled={isProcessing}
                className="cursor-pointer rounded-md bg-red-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Reject
              </button>
            </div>
          </div>
        </div>
      </div>

      <EditEquipmentModal
        equipment={equipment}
        isOpen={isEditOpen}
        onClose={() => setIsEditOpen(false)}
        onSave={() => setIsEditOpen(false)}
        update={update}
      />
      <RegenerateImageModal
        equipmentId={equipment.equipmentId}
        equipmentName={equipment.name}
        isOpen={isRegenOpen}
        onClose={() => setIsRegenOpen(false)}
        onStarted={() => setIsRegenOpen(false)}
        getPrompt={getImagePrompt}
        regenerate={regenerate}
      />
      <ImageLightbox
        src={lightboxSrc}
        alt={equipment.name}
        onClose={() => setLightboxSrc(null)}
      />
    </>
  );
}

function ImageThumb({
  url,
  status,
  onZoom,
  alt,
}: {
  url: string | null;
  status: AdminEquipment['imageStatus'];
  onZoom: (src: string) => void;
  alt: string;
}) {
  if (url) {
    const isZoomable = status === 'GENERATED';
    if (isZoomable) {
      return (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onZoom(url);
          }}
          className="block h-32 w-32 shrink-0 cursor-zoom-in rounded-md border border-border-default p-0"
          aria-label={`Zoom image for ${alt}`}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={url}
            alt={alt}
            className="h-full w-full rounded-md object-cover"
          />
        </button>
      );
    }
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={url}
        alt=""
        className="h-32 w-32 shrink-0 rounded-md border border-border-default object-cover"
      />
    );
  }
  return (
    <div className="flex h-32 w-32 shrink-0 flex-col items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
      <i className="ti ti-photo text-2xl" aria-hidden />
      <span className="mt-1 text-[10px] uppercase tracking-wider">
        {status === 'PENDING' ? 'Pending' : status === 'FAILED' ? 'Failed' : 'No image'}
      </span>
    </div>
  );
}

function SpecSchemaChip({ schema }: { schema: SpecSchema }) {
  const names: Record<SpecSchema, string> = {
    selectorized: 'Selectorized',
    plate_loaded: 'Plate-Loaded',
    bodyweight: 'Bodyweight',
    cable: 'Cable',
    cardio: 'Cardio',
    weight_set: 'Weight Set',
  };
  return (
    <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
      {names[schema]}
    </span>
  );
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  } catch {
    return iso;
  }
}

function formatSpecs(specSchema: SpecSchema, specs: Record<string, unknown>): string {
  switch (specSchema) {
    case 'selectorized':
    case 'weight_set':
      return `${specs.minWeight}–${specs.maxWeight} lb, ${specs.increment} lb increments`;
    case 'plate_loaded': {
      const plates = (specs.availablePlates as number[])?.join(', ') || '—';
      return `Bar ${specs.barWeight} lb · Plates: ${plates}`;
    }
    case 'bodyweight':
      return 'No specs';
    case 'cable':
      return `Stack ${specs.weightStack} lb · ${specs.numStations} station(s)`;
    case 'cardio':
      return `${specs.resistanceLevels} levels · Incline: ${specs.hasIncline ? 'yes' : 'no'}`;
  }
}
