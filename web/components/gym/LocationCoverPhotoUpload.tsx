"use client";

import { useRef, useState, useTransition } from "react";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";

type Props = {
  currentPhotoUrl: string | null;
  uploadPhoto: (formData: FormData) => Promise<string>;
  deletePhoto: () => Promise<void>;
};

export function LocationCoverPhotoUpload({
  currentPhotoUrl,
  uploadPhoto,
  deletePhoto,
}: Props) {
  const toast = useToast();
  const confirm = useConfirm();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [photoUrl, setPhotoUrl] = useState<string | null>(currentPhotoUrl);
  const [dragOver, setDragOver] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [, startTransition] = useTransition();

  async function handleFile(file: File) {
    if (!file.type.startsWith("image/")) {
      toast.error("Unsupported file", {
        description: "Please choose a JPG, PNG, or WebP image.",
      });
      return;
    }

    setIsUploading(true);
    try {
      const formData = new FormData();
      formData.set("file", file);
      const newUrl = await uploadPhoto(formData);
      // Append a cache-buster so the <img> swaps even if the URL is reused.
      const bustedUrl = `${newUrl}${newUrl.includes("?") ? "&" : "?"}t=${Date.now()}`;
      setPhotoUrl(bustedUrl);
      toast.success("Cover photo updated");
    } catch (err) {
      toast.error("Upload failed", {
        description: err instanceof Error ? err.message : "Please try again.",
      });
    } finally {
      setIsUploading(false);
    }
  }

  function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    void handleFile(file);
  }

  function onDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragOver(false);
    if (isUploading) return;
    const file = e.dataTransfer.files?.[0];
    if (!file) return;
    void handleFile(file);
  }

  function onDragOver(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    if (!dragOver) setDragOver(true);
  }

  function onDragLeave(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragOver(false);
  }

  function onClickZone() {
    if (isUploading) return;
    fileInputRef.current?.click();
  }

  async function onRemove(e: React.MouseEvent) {
    e.stopPropagation();
    if (isUploading) return;
    const ok = await confirm({
      title: "Remove cover photo?",
      description: "The gym will fall back to the default placeholder image.",
      confirmLabel: "Remove",
      tone: "danger",
    });
    if (!ok) return;

    setIsUploading(true);
    startTransition(async () => {
      try {
        await deletePhoto();
        setPhotoUrl(null);
        toast.success("Cover photo removed");
      } catch (err) {
        toast.error("Remove failed", {
          description: err instanceof Error ? err.message : "Please try again.",
        });
      } finally {
        setIsUploading(false);
      }
    });
  }

  const hasPhoto = Boolean(photoUrl);
  const interactiveClasses = isUploading ? "opacity-60 pointer-events-none" : "";
  const borderClasses = dragOver
    ? "border-accent bg-accent/5"
    : hasPhoto
      ? "border-border-default"
      : "border-border-strong bg-canvas";

  return (
    <div className="space-y-2">
      <div
        role="button"
        tabIndex={0}
        onClick={onClickZone}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onClickZone();
          }
        }}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
        className={`group relative w-full cursor-pointer overflow-hidden rounded-[14px] border-[0.5px] border-dashed ${borderClasses} ${interactiveClasses}`}
        style={{ aspectRatio: "21 / 9" }}
      >
        {hasPhoto && photoUrl ? (
          <>
            <div
              className="absolute inset-0 bg-cover bg-center"
              style={{ backgroundImage: `url("${photoUrl}")` }}
            />
            <div className="absolute inset-0 flex items-center justify-center bg-canvas/0 opacity-0 transition-opacity group-hover:bg-canvas/55 group-hover:opacity-100">
              <span className="text-[13px] font-medium text-inverse">
                {isUploading ? "Uploading..." : "Click or drop to replace"}
              </span>
            </div>
            <button
              type="button"
              onClick={onRemove}
              disabled={isUploading}
              className="absolute right-3 top-3 cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface/95 px-2.5 py-1 text-[11px] font-medium text-primary shadow-sm backdrop-blur-sm hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
            >
              Remove
            </button>
          </>
        ) : (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-1 px-4 text-center">
            <span className="text-[14px] font-medium text-primary">
              {isUploading
                ? "Uploading..."
                : dragOver
                  ? "Drop to upload"
                  : "Drop an image here or click to select"}
            </span>
            <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
              JPG, PNG, WebP
            </span>
          </div>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={onPickFile}
      />
    </div>
  );
}
