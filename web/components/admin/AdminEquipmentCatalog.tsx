"use client";

import { useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import type { AdminEquipment, SpecSchema, EquipmentSpecs } from '@/lib/types/gym';
import { useToast } from '@/components/ui/Toast';
import { EditEquipmentModal } from './EditEquipmentModal';
import { AddEquipmentModal } from './AddEquipmentModal';
import { RegenerateImageModal } from './RegenerateImageModal';
import { ImageLightbox } from './ImageLightbox';
import { ImageCandidateStrip } from './ImageCandidateStrip';

interface Props {
  catalog: AdminEquipment[];
  create: (
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) => Promise<void>;
  update: (
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) => Promise<void>;
  regenerate: (equipmentId: string, prompt: string) => Promise<void>;
  uploadImage: (equipmentId: string, file: File) => Promise<void>;
  getImageStatus: (equipmentId: string) => Promise<string | null>;
  getImagePrompt: (equipmentId: string) => Promise<string>;
  selectImage: (equipmentId: string, imageUrl: string) => Promise<void>;
  deleteImage: (equipmentId: string, imageUrl: string) => Promise<void>;
}

export function AdminEquipmentCatalog({
  catalog,
  create,
  update,
  regenerate,
  uploadImage,
  getImageStatus,
  getImagePrompt,
  selectImage,
  deleteImage,
}: Props) {
  const router = useRouter();
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadTargetId, setUploadTargetId] = useState<string | null>(null);

  function pickFileFor(equipmentId: string) {
    setUploadTargetId(equipmentId);
    fileInputRef.current?.click();
  }

  async function handleUploadFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    const id = uploadTargetId;
    // Reset so re-selecting the same file fires onChange again.
    e.target.value = '';
    setUploadTargetId(null);
    if (!file || !id) return;
    try {
      await uploadImage(id, file);
      toast.success('Image uploaded');
      router.refresh();
    } catch (err) {
      toast.error('Upload failed', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    }
  }
  const [query, setQuery] = useState('');
  const [showAdd, setShowAdd] = useState(false);
  const [editingEquipment, setEditingEquipment] = useState<AdminEquipment | null>(null);
  const [regeneratingEquipment, setRegeneratingEquipment] = useState<AdminEquipment | null>(null);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [lightboxAlt, setLightboxAlt] = useState<string>('');

  const filtered = useMemo(() => {
    const sorted = [...catalog].sort((a, b) => a.name.localeCompare(b.name));
    const q = query.trim().toLowerCase();
    if (!q) return sorted;
    return sorted.filter(e => e.name.toLowerCase().includes(q));
  }, [catalog, query]);

  return (
    <div>
      <div className="mb-4 flex items-center gap-3">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search equipment…"
          className="w-full max-w-md rounded-md border border-border-default bg-surface px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
        />
        <button
          type="button"
          onClick={() => setShowAdd(true)}
          className="ml-auto shrink-0 cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90"
        >
          Add equipment
        </button>
      </div>

      {filtered.length === 0 ? (
        <div className="rounded-lg border border-border-default bg-surface px-6 py-12 text-center">
          <p className="text-sm text-secondary">
            {catalog.length === 0 ? 'No catalog items yet.' : 'No equipment matches your search.'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
          {filtered.map((eq) => {
            const isZoomable = !!eq.imageUrl && eq.imageStatus === 'GENERATED';
            return (
              <div
                key={eq.equipmentId}
                className="flex flex-col overflow-hidden rounded-lg border border-border-default bg-surface shadow-md"
              >
                {eq.imageUrl ? (
                  isZoomable ? (
                    <button
                      type="button"
                      onClick={() => {
                        if (eq.imageUrl) {
                          setLightboxSrc(eq.imageUrl);
                          setLightboxAlt(eq.name);
                        }
                      }}
                      className="block aspect-square w-full cursor-zoom-in border-b border-border-default p-0"
                      aria-label={`Zoom image for ${eq.name}`}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={eq.imageUrl}
                        alt={eq.name}
                        className="aspect-square w-full rounded-t-md object-cover"
                      />
                    </button>
                  ) : (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={eq.imageUrl}
                      alt={eq.name}
                      className="aspect-square w-full rounded-t-md border-b border-border-default object-cover"
                    />
                  )
                ) : (
                  <div className="flex aspect-square w-full items-center justify-center border-b border-dashed border-border-default bg-canvas">
                    <span className="text-xs text-tertiary">
                      {eq.imageStatus === 'PENDING' ? 'Generating…' : 'No image'}
                    </span>
                  </div>
                )}

                <div className="flex flex-1 flex-col gap-2 p-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-primary" title={eq.name}>
                      {eq.name}
                    </p>
                    <p className="truncate text-xs text-tertiary">
                      {eq.category} · {eq.subcategory}
                    </p>
                  </div>
                  <ImageCandidateStrip
                    id={eq.equipmentId}
                    name={eq.name}
                    activeUrl={eq.imageUrl}
                    candidates={eq.imageCandidates ?? []}
                    selectImage={async (id, url) => { await selectImage(id, url); router.refresh(); }}
                    deleteImage={async (id, url) => { await deleteImage(id, url); router.refresh(); }}
                  />
                  <div className="mt-auto flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => pickFileFor(eq.equipmentId)}
                      className="cursor-pointer rounded-md border border-border-default bg-canvas px-3 py-1.5 text-xs font-medium text-primary hover:bg-surface"
                    >
                      Upload photo
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditingEquipment(eq)}
                      className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-inverse hover:bg-accent/90"
                    >
                      Edit
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <AddEquipmentModal
        isOpen={showAdd}
        onClose={() => setShowAdd(false)}
        onSave={async () => {
          setShowAdd(false);
          router.refresh();
        }}
        create={create}
      />

      {editingEquipment && (
        <EditEquipmentModal
          equipment={editingEquipment}
          isOpen={true}
          onClose={() => setEditingEquipment(null)}
          onSave={async () => {
            setEditingEquipment(null);
            router.refresh();
          }}
          update={update}
          onRegenerate={() => {
            setRegeneratingEquipment(editingEquipment);
            setEditingEquipment(null);
          }}
        />
      )}

      {regeneratingEquipment && (
        <RegenerateImageModal
          equipmentId={regeneratingEquipment.equipmentId}
          equipmentName={regeneratingEquipment.name}
          isOpen={true}
          onClose={() => setRegeneratingEquipment(null)}
          onStarted={() => {
            const id = regeneratingEquipment.equipmentId;
            setRegeneratingEquipment(null);
            // Poll image status in the background; refresh when it leaves
            // PENDING/GENERATING. Image generation is async on the backend
            // (~15-20s). Time out after 60s and refresh anyway so the admin
            // can manually retry.
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
          getPrompt={getImagePrompt}
          regenerate={regenerate}
        />
      )}

      <ImageLightbox
        src={lightboxSrc}
        alt={lightboxAlt}
        onClose={() => setLightboxSrc(null)}
      />

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleUploadFile}
      />
    </div>
  );
}
