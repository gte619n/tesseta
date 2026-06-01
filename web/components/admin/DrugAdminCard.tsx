"use client";

import { useRef, useState } from 'react';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import type { Drug, DrugCategory, DrugForm } from '@/lib/types/medication';
import { CATEGORY_LABELS, FORM_LABELS } from '@/lib/types/medication';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { EditDrugModal } from './EditDrugModal';
import { ImageCandidateStrip } from './ImageCandidateStrip';
import { ImageLightbox } from './ImageLightbox';
import { RegenerateImageModal } from './RegenerateImageModal';

interface Props {
  drug: Drug;
  update: (
    drugId: string,
    data: { name: string; aliases: string[]; category: DrugCategory; form: DrugForm; defaultUnit: string },
  ) => Promise<void>;
  regenerate: (drugId: string, prompt: string) => Promise<void>;
  uploadImage: (drugId: string, file: File) => Promise<void>;
  selectImage: (drugId: string, imageUrl: string) => Promise<void>;
  deleteImage: (drugId: string, imageUrl: string) => Promise<void>;
  getImagePrompt: (drugId: string) => Promise<string>;
  remove: (drugId: string) => Promise<void>;
  isDragOver?: boolean;
}

export function DrugAdminCard({
  drug,
  update,
  regenerate,
  uploadImage,
  selectImage,
  deleteImage,
  getImagePrompt,
  remove,
  isDragOver,
}: Props) {
  const confirm = useConfirm();
  const toast = useToast();
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isRegenOpen, setIsRegenOpen] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setIsProcessing(true);
    try {
      await uploadImage(drug.drugId, file);
      toast.success('Image uploaded');
    } catch (err) {
      toast.error('Failed to upload image', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
      // Reset so selecting the same file again re-triggers onChange.
      e.target.value = '';
    }
  }

  const draggable = useDraggable({ id: drug.drugId, data: { drug } });
  const droppable = useDroppable({ id: `drop-${drug.drugId}`, data: { drug } });

  async function handleDelete() {
    const ok = await confirm({
      title: 'Delete drug',
      description: `Permanently delete "${drug.name}" from the catalog? This cannot be undone, and the API will refuse if any user medication still references it.`,
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!ok) return;
    setIsProcessing(true);
    try {
      await remove(drug.drugId);
      toast.success('Drug deleted');
    } catch (e) {
      toast.error('Failed to delete drug', {
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

  const imageSrc = drug.imageUrl || drug.imageFallback;

  return (
    <>
      <div ref={droppable.setNodeRef} className={baseClasses}>
        <div ref={draggable.setNodeRef} className="flex gap-5">
          {imageSrc ? (
            drug.imageUrl ? (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  if (drug.imageUrl) setLightboxSrc(drug.imageUrl);
                }}
                className="block h-32 w-32 shrink-0 cursor-zoom-in rounded-md border border-border-default p-0"
                aria-label={`Zoom image for ${drug.name}`}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={imageSrc}
                  alt={drug.name}
                  className="h-full w-full rounded-md object-cover"
                />
              </button>
            ) : (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={imageSrc}
                alt=""
                className="h-32 w-32 shrink-0 rounded-md border border-border-default object-cover"
              />
            )
          ) : (
            <div className="flex h-32 w-32 shrink-0 items-center justify-center rounded-md border border-dashed border-border-default bg-canvas text-tertiary">
              <i className="ti ti-pill text-2xl" aria-hidden />
            </div>
          )}

          <div className="flex-1 min-w-0">
            <div
              {...draggable.listeners}
              {...draggable.attributes}
              className="cursor-grab active:cursor-grabbing"
            >
              <div className="flex items-center gap-2">
                <h3 className="truncate text-base font-semibold text-primary">{drug.name}</h3>
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
                  {CATEGORY_LABELS[drug.category]}
                </span>
                <span className="caps-mono rounded-[3px] bg-canvas-muted px-1.5 py-px text-[9px] tracking-[0.06em] text-tertiary">
                  {FORM_LABELS[drug.form]}
                </span>
              </div>
              {drug.aliases.length > 0 ? (
                <p className="mt-1 text-xs text-secondary">
                  Also known as <span className="text-primary">{drug.aliases.join(', ')}</span>
                </p>
              ) : (
                <p className="mt-1 text-xs text-tertiary">No aliases</p>
              )}
              <p className="mt-2 text-xs text-tertiary">
                Default unit: <span className="text-secondary">{drug.defaultUnit}</span>
                {drug.commonDoses.length > 0 ? (
                  <>
                    <span className="text-quaternary"> · </span>
                    Common: <span className="text-secondary">{drug.commonDoses.join(', ')}</span>
                  </>
                ) : null}
              </p>
              {drug.suggestedMarkers.length > 0 ? (
                <p className="mt-1 text-xs text-tertiary">
                  Markers: <span className="text-secondary">{drug.suggestedMarkers.join(', ')}</span>
                </p>
              ) : null}
            </div>

            <div className="mt-4 flex flex-wrap items-center gap-2">
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
                onChange={handleUpload}
                className="hidden"
              />
              <button
                onClick={handleDelete}
                disabled={isProcessing}
                className="cursor-pointer rounded-md bg-red-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Delete
              </button>
            </div>

            {drug.imageCandidates.length > 1 ? (
              <div className="mt-4">
                <p className="mb-2 text-xs text-tertiary">
                  Gallery <span className="text-quaternary">· click to set active</span>
                </p>
                <ImageCandidateStrip
                  id={drug.drugId}
                  name={drug.name}
                  activeUrl={drug.imageUrl}
                  candidates={drug.imageCandidates}
                  selectImage={selectImage}
                  deleteImage={deleteImage}
                />
              </div>
            ) : null}
          </div>
        </div>
      </div>

      <EditDrugModal
        drug={drug}
        isOpen={isEditOpen}
        onClose={() => setIsEditOpen(false)}
        onSave={() => setIsEditOpen(false)}
        update={update}
      />
      <RegenerateImageModal
        equipmentId={drug.drugId}
        equipmentName={drug.name}
        isOpen={isRegenOpen}
        onClose={() => setIsRegenOpen(false)}
        onStarted={() => setIsRegenOpen(false)}
        getPrompt={getImagePrompt}
        regenerate={regenerate}
      />
      <ImageLightbox
        src={lightboxSrc}
        alt={drug.name}
        onClose={() => setLightboxSrc(null)}
      />
    </>
  );
}
