"use client";

import { useEffect, useRef, useState } from 'react';
import { useToast } from '@/components/ui/Toast';

interface Props {
  equipmentId: string;
  equipmentName: string;
  isOpen: boolean;
  onClose: () => void;
  onStarted: () => void;
  getPrompt: (equipmentId: string) => Promise<string>;
  regenerate: (equipmentId: string, prompt: string) => Promise<void>;
}

export function RegenerateImageModal({
  equipmentId,
  equipmentName,
  isOpen,
  onClose,
  onStarted,
  getPrompt,
  regenerate,
}: Props) {
  const toast = useToast();
  const [prompt, setPrompt] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  // Track whether the mousedown landed on the backdrop so we only close on a
  // true backdrop click. Without this, a text-selection drag that starts
  // inside the dialog and releases over the backdrop would close the modal.
  const downOnBackdropRef = useRef(false);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
    }
  }

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setIsLoading(true);
    getPrompt(equipmentId)
      .then(p => {
        if (!cancelled) setPrompt(p);
      })
      .catch(() => {
        if (!cancelled) setPrompt('');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, equipmentId, getPrompt]);

  if (!isOpen) return null;

  async function handleSubmit() {
    setIsSubmitting(true);
    try {
      await regenerate(equipmentId, prompt);
      toast.success('Image regeneration started');
      onStarted();
      onClose();
    } catch (e) {
      toast.error('Failed to start regeneration', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="w-[680px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-1 text-xl font-semibold text-primary">Regenerate image</h2>
        <p className="mb-4 text-sm text-secondary">
          For <span className="font-medium text-primary">{equipmentName}</span>. Edit the prompt
          before re-running if you want a different look.
        </p>

        <label className="mb-1 block text-xs font-medium text-secondary">
          Prompt
        </label>
        <textarea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          disabled={isLoading || isSubmitting}
          rows={14}
          className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 font-mono text-xs text-primary focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
        />

        <div className="mt-6 flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={isSubmitting}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={isLoading || isSubmitting || !prompt.trim()}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSubmitting ? 'Submitting…' : 'Regenerate'}
          </button>
        </div>
      </div>
    </div>
  );
}
