"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import type { ExerciseResponse } from "@/lib/types/exercise";
import { thumbUrl } from "@/lib/exercise-thumb";
import { collectCandidates } from "./ReferencePicker";

// IMPL-20: unified grounding gallery. The whole grounding set is shown as
// X-able thumbnails; a dropzone (drag / drop / paste / click) uploads brand-new
// grounding photos; a collapsible strip pulls in existing demo-frame / external
// reference candidates. Uploaded photos are permanently deleted on X; candidate
// / external images are only unlinked (the backend decides by object path).

// Marker in an own grounding-upload URL (exercises/{id}/grounding-upload_{ts}.ext),
// used only to word the confirm dialog — the backend is the real authority.
const UPLOAD_MARKER = "/grounding-upload_";

interface Props {
  exercise: ExerciseResponse;
  onExerciseUpdated: (ex: ExerciseResponse) => void;
  uploadGroundingImage: (
    exerciseId: string,
    file: File,
  ) => Promise<ExerciseResponse>;
  removeGroundingImage: (
    exerciseId: string,
    imageUrl: string,
  ) => Promise<ExerciseResponse>;
  saveGrounding: (exerciseId: string, imageUrls: string[]) => Promise<void>;
}

export function GroundingGallery({
  exercise,
  onExerciseUpdated,
  uploadGroundingImage,
  removeGroundingImage,
  saveGrounding,
}: Props) {
  const toast = useToast();
  const confirm = useConfirm();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showAdd, setShowAdd] = useState(false);

  const exerciseId = exercise.exerciseId;
  const grounding = exercise.groundingImageUrls ?? [];
  const groundingSet = useMemo(() => new Set(grounding), [grounding]);

  // Frame / external candidates not already in the grounding set.
  const candidates = useMemo(
    () => collectCandidates(exercise).filter((c) => !groundingSet.has(c.url)),
    [exercise, groundingSet],
  );

  async function uploadFiles(files: File[]) {
    const images = files.filter((f) => f.type.startsWith("image/"));
    if (images.length === 0) {
      toast.error("Unsupported file", {
        description: "Please choose JPG, PNG, or WebP images.",
      });
      return;
    }
    setBusy(true);
    try {
      let latest: ExerciseResponse | null = null;
      for (const file of images) {
        latest = await uploadGroundingImage(exerciseId, file);
      }
      if (latest) onExerciseUpdated(latest);
      toast.success(
        images.length > 1
          ? `${images.length} grounding photos added`
          : "Grounding photo added",
      );
    } catch (e) {
      toast.error("Upload failed", {
        description: e instanceof Error ? e.message : "Please try again.",
      });
    } finally {
      setBusy(false);
    }
  }

  // Paste anywhere while the panel is mounted, as long as the clipboard carries
  // image files (a plain text paste has none, so it falls through untouched).
  useEffect(() => {
    function onPaste(e: ClipboardEvent) {
      if (busy) return;
      const files: File[] = [];
      for (const item of e.clipboardData?.items ?? []) {
        if (item.kind === "file") {
          const f = item.getAsFile();
          if (f && f.type.startsWith("image/")) files.push(f);
        }
      }
      if (files.length > 0) {
        e.preventDefault();
        void uploadFiles(files);
      }
    }
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busy, exerciseId]);

  function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length > 0) void uploadFiles(files);
  }

  function onDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragOver(false);
    if (busy) return;
    const files = Array.from(e.dataTransfer.files ?? []);
    if (files.length > 0) void uploadFiles(files);
  }

  async function handleRemove(url: string) {
    const isUpload = url.includes(UPLOAD_MARKER);
    const ok = await confirm({
      title: isUpload ? "Delete grounding photo?" : "Remove from grounding?",
      description: isUpload
        ? "This permanently deletes the uploaded photo from storage."
        : "This removes the image from the grounding set. The underlying frame / reference is kept.",
      confirmLabel: isUpload ? "Delete" : "Remove",
      tone: "danger",
    });
    if (!ok) return;
    setBusy(true);
    try {
      onExerciseUpdated(await removeGroundingImage(exerciseId, url));
    } catch (e) {
      toast.error("Remove failed", {
        description: e instanceof Error ? e.message : "Please try again.",
      });
    } finally {
      setBusy(false);
    }
  }

  async function addCandidate(url: string) {
    setBusy(true);
    try {
      await saveGrounding(exerciseId, [...grounding, url]);
    } catch (e) {
      toast.error("Failed to add", {
        description: e instanceof Error ? e.message : "Please try again.",
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={"space-y-3 " + (busy ? "opacity-70" : "")}>
      {/* Dropzone */}
      <div
        role="button"
        tabIndex={0}
        onClick={() => !busy && fileInputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            if (!busy) fileInputRef.current?.click();
          }
        }}
        onDragOver={(e) => {
          e.preventDefault();
          if (!dragOver) setDragOver(true);
        }}
        onDragLeave={(e) => {
          e.preventDefault();
          setDragOver(false);
        }}
        onDrop={onDrop}
        className={
          "flex cursor-pointer items-center justify-center rounded-md border border-dashed px-3 py-4 text-center text-[11px] " +
          (dragOver
            ? "border-accent bg-accent/5 text-primary"
            : "border-border-strong bg-surface text-tertiary hover:text-secondary")
        }
      >
        {busy
          ? "Working…"
          : dragOver
            ? "Drop to upload"
            : "Drop, paste, or click to add grounding photos · JPG, PNG, WebP"}
      </div>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        multiple
        className="hidden"
        onChange={onPickFile}
      />

      {/* Current grounding set */}
      {grounding.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {grounding.map((url) => (
            <div
              key={url}
              className="relative h-20 w-16 overflow-hidden rounded border border-border-default"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={thumbUrl(url)}
                alt="grounding reference"
                loading="lazy"
                onError={(e) => {
                  const img = e.currentTarget;
                  if (img.src !== url) img.src = url;
                }}
                className="h-full w-full object-cover"
              />
              <button
                type="button"
                onClick={() => handleRemove(url)}
                disabled={busy}
                aria-label="Remove grounding image"
                title="Remove"
                className="absolute right-0.5 top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-surface/90 text-[10px] leading-none text-primary shadow-sm hover:bg-surface disabled:opacity-50"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-[11px] text-tertiary">
          No grounding photos yet — add some above.
        </p>
      )}

      {/* Add from existing frame / external candidates */}
      {candidates.length > 0 ? (
        <div>
          <button
            type="button"
            onClick={() => setShowAdd((v) => !v)}
            className="cursor-pointer text-[11px] font-medium text-secondary hover:text-primary"
          >
            {showAdd ? "− Hide" : "+ Add"} from frames / references (
            {candidates.length})
          </button>
          {showAdd ? (
            <div className="mt-1.5 flex flex-wrap gap-2">
              {candidates.map((c) => (
                <button
                  key={c.url}
                  type="button"
                  onClick={() => addCandidate(c.url)}
                  disabled={busy}
                  title={`Add from ${c.group}`}
                  className="relative block h-20 w-16 overflow-hidden rounded border border-border-default p-0 opacity-70 hover:opacity-100 disabled:opacity-40"
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={thumbUrl(c.url)}
                    alt={c.group}
                    loading="lazy"
                    onError={(e) => {
                      const img = e.currentTarget;
                      if (img.src !== c.url) img.src = c.url;
                    }}
                    className="h-full w-full object-cover"
                  />
                  <span className="absolute bottom-0 left-0 right-0 bg-canvas/80 py-0.5 text-center text-[8px] text-secondary">
                    + add
                  </span>
                </button>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
