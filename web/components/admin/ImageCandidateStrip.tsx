"use client";

import { useState } from 'react';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';

interface Props {
  id: string;
  name: string;
  // The active image; the matching candidate gets a selected ring.
  activeUrl: string | null;
  candidates: string[];
  selectImage: (id: string, imageUrl: string) => Promise<void>;
  deleteImage: (id: string, imageUrl: string) => Promise<void>;
}

// A horizontal strip of candidate image thumbnails. The active image is
// highlighted with an accent ring; clicking a non-active thumbnail makes it
// the active image, and the × button removes a candidate entirely. Rendered
// only when there's more than one candidate.
export function ImageCandidateStrip({
  id,
  name,
  activeUrl,
  candidates,
  selectImage,
  deleteImage,
}: Props) {
  const confirm = useConfirm();
  const toast = useToast();
  const [busyUrl, setBusyUrl] = useState<string | null>(null);

  if (candidates.length <= 1) return null;

  async function handleSelect(url: string) {
    if (url === activeUrl || busyUrl) return;
    setBusyUrl(url);
    try {
      await selectImage(id, url);
      toast.success('Image selected');
    } catch (err) {
      toast.error('Failed to select image', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusyUrl(null);
    }
  }

  async function handleDelete(url: string) {
    if (busyUrl) return;
    const ok = await confirm({
      title: 'Delete image',
      description: `Remove this image from "${name}"? This permanently deletes the file.`,
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!ok) return;
    setBusyUrl(url);
    try {
      await deleteImage(id, url);
      toast.success('Image deleted');
    } catch (err) {
      toast.error('Failed to delete image', {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    } finally {
      setBusyUrl(null);
    }
  }

  return (
    <div className="flex flex-wrap gap-2">
      {candidates.map((url) => {
        const isActive = url === activeUrl;
        return (
          <div key={url} className="group relative">
            <button
              type="button"
              onClick={() => handleSelect(url)}
              disabled={busyUrl !== null}
              aria-label={isActive ? 'Active image' : 'Make this the active image'}
              aria-pressed={isActive}
              className={
                'block h-12 w-12 overflow-hidden rounded-md border p-0 disabled:opacity-50 ' +
                (isActive
                  ? 'border-accent ring-2 ring-accent cursor-default'
                  : 'border-border-default cursor-pointer hover:border-accent')
              }
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={url} alt="" className="h-full w-full object-cover" />
            </button>
            <button
              type="button"
              onClick={() => handleDelete(url)}
              disabled={busyUrl !== null}
              aria-label="Delete this image"
              className="absolute -right-1.5 -top-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-red-600 text-[10px] leading-none text-white opacity-0 transition-opacity hover:bg-red-700 group-hover:opacity-100 disabled:cursor-not-allowed"
            >
              ×
            </button>
          </div>
        );
      })}
    </div>
  );
}
