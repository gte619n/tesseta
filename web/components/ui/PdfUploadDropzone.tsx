"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { readSseStream } from "@/lib/sse-client";

// Dashed-border tile + full-page drop overlay + per-file progress overlay.
// Each upload streams its own SSE response from `uploadUrl` (a Next route that
// proxies to the backend with auth).
//
// Multi-file behavior: drop or pick N PDFs → all upload in parallel, each
// tracked independently in the overlay. The page refresh happens after the LAST
// upload completes; failures don't block the others.
//
// Phase strings mirror the backend SSE contract (DexaScanController /
// BloodTestController):
//   uploading  → "Saving your PDF"
//   extracting → "Reading … with Gemini"
//   saving     → "Storing the results"
//   complete   → done
//   failed     → fatal, payload includes an error message

type Phase = "uploading" | "extracting" | "saving";
type Status = Phase | "queued" | "complete" | "failed";

type FileState = {
  id: string;
  name: string;
  status: Status;
  error?: string;
};

const PHASE_LABEL: Record<Status, string> = {
  queued: "Queued",
  uploading: "Saving PDF",
  extracting: "Reading with Gemini",
  saving: "Storing results",
  complete: "Done",
  failed: "Failed",
};

export type PdfUploadDropzoneProps = {
  /** Next route that proxies the upload to the backend (streams SSE phases). */
  uploadUrl: string;
  /** Trigger-button text, e.g. "Drop DEXA PDFs". */
  triggerLabel: string;
  /** Trigger-button title attribute (hover tooltip). */
  triggerTitle: string;
  /** Heading shown in the full-page drag overlay, e.g. "Drop your DEXA PDFs". */
  overlayLabel: string;
  /** Singular noun for progress copy, e.g. "scan" / "report". */
  itemNoun: string;
};

export function PdfUploadDropzone({
  uploadUrl,
  triggerLabel,
  triggerTitle,
  overlayLabel,
  itemNoun,
}: PdfUploadDropzoneProps) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [files, setFiles] = useState<FileState[]>([]);
  const dragDepth = useRef(0);

  const busy = files.some(
    (f) => f.status !== "complete" && f.status !== "failed",
  );
  const overlayOpen = files.length > 0;

  function updateOne(id: string, patch: Partial<FileState>) {
    setFiles((prev) =>
      prev.map((f) => (f.id === id ? { ...f, ...patch } : f)),
    );
  }

  async function uploadOne(state: FileState, file: File) {
    if (!file.name.toLowerCase().endsWith(".pdf") && file.type !== "application/pdf") {
      updateOne(state.id, { status: "failed", error: "Not a PDF" });
      return;
    }

    const fd = new FormData();
    fd.set("file", file);
    updateOne(state.id, { status: "uploading" });

    let res: Response;
    try {
      res = await fetch(uploadUrl, { method: "POST", body: fd });
    } catch (err) {
      updateOne(state.id, {
        status: "failed",
        error: err instanceof Error ? err.message : "Network error",
      });
      return;
    }
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => "");
      updateOne(state.id, {
        status: "failed",
        error: text || `Upload failed (${res.status})`,
      });
      return;
    }

    await readSseStream(res.body, (_event, data) => {
      if (!data) return;
      try {
        const event = JSON.parse(data) as
          | { phase: Phase }
          | { phase: "complete" }
          | { phase: "failed"; error: string };
        if (event.phase === "complete") {
          updateOne(state.id, { status: "complete" });
        } else if (event.phase === "failed") {
          updateOne(state.id, {
            status: "failed",
            error: (event as { error: string }).error,
          });
        } else {
          updateOne(state.id, { status: event.phase });
        }
      } catch {
        // Malformed event — skip and keep the stream alive.
      }
    });
  }

  async function submit(picked: File[]) {
    if (picked.length === 0) return;
    const initial: FileState[] = picked.map((f) => ({
      id:
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`,
      name: f.name,
      status: "queued",
    }));
    setFiles((prev) => [...prev, ...initial]);

    // Run all uploads in parallel. The backend's virtual-thread pool can
    // comfortably handle several concurrent Gemini calls; if we ever start
    // hitting API quota, we'll add a small concurrency limit here.
    await Promise.all(
      initial.map((state, i) => uploadOne(state, picked[i]!)),
    );

    router.refresh();
  }

  useEffect(() => {
    function onDragEnter(e: DragEvent) {
      if (!e.dataTransfer?.types.includes("Files")) return;
      dragDepth.current += 1;
      setDragOver(true);
    }
    function onDragLeave() {
      dragDepth.current = Math.max(0, dragDepth.current - 1);
      if (dragDepth.current === 0) setDragOver(false);
    }
    function onDragOver(e: DragEvent) {
      if (!e.dataTransfer?.types.includes("Files")) return;
      e.preventDefault();
    }
    function onDrop(e: DragEvent) {
      if (!e.dataTransfer?.types.includes("Files")) return;
      e.preventDefault();
      dragDepth.current = 0;
      setDragOver(false);
      const list = Array.from(e.dataTransfer.files);
      if (list.length > 0) submit(list);
    }
    window.addEventListener("dragenter", onDragEnter);
    window.addEventListener("dragleave", onDragLeave);
    window.addEventListener("dragover", onDragOver);
    window.addEventListener("drop", onDrop);
    return () => {
      window.removeEventListener("dragenter", onDragEnter);
      window.removeEventListener("dragleave", onDragLeave);
      window.removeEventListener("dragover", onDragOver);
      window.removeEventListener("drop", onDrop);
    };
    // submit closes over `router`, but router is stable across renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const picked = Array.from(e.target.files ?? []);
    if (inputRef.current) inputRef.current.value = "";
    if (picked.length > 0) submit(picked);
  }

  function dismiss() {
    setFiles([]);
  }

  const doneCount = files.filter((f) => f.status === "complete").length;
  const failedCount = files.filter((f) => f.status === "failed").length;
  const allSettled =
    files.length > 0 && doneCount + failedCount === files.length;

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
        multiple
        onChange={onPick}
        disabled={busy}
        aria-hidden
        tabIndex={-1}
        style={{
          position: "absolute",
          width: 1,
          height: 1,
          padding: 0,
          margin: -1,
          overflow: "hidden",
          clip: "rect(0,0,0,0)",
          whiteSpace: "nowrap",
          border: 0,
        }}
      />

      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={busy}
        title={triggerTitle}
        className="group flex items-center gap-2 rounded-[8px] border border-dashed border-border-strong bg-canvas px-2.5 py-1.5 text-[11px] font-medium text-secondary transition hover:border-accent hover:bg-surface hover:text-primary disabled:opacity-60"
      >
        <svg
          width="11"
          height="11"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden
          className="text-tertiary group-hover:text-accent"
        >
          <path
            d="M8 11V3M8 3 5 6M8 3l3 3M3 12v1a1 1 0 001 1h8a1 1 0 001-1v-1"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <span>{triggerLabel}</span>
      </button>

      {dragOver && !overlayOpen && (
        <div className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center bg-canvas/85 backdrop-blur-sm">
          <div className="rounded-[14px] border-2 border-dashed border-accent bg-surface px-10 py-8 text-center shadow-[0_24px_64px_rgba(0,0,0,0.12)]">
            <div className="text-[22px] font-medium tracking-[-0.015em] text-primary">
              {overlayLabel}
            </div>
            <div className="mt-2 font-mono text-[12px] text-tertiary">
              Multiple files supported — we&apos;ll read them in parallel.
            </div>
          </div>
        </div>
      )}

      {overlayOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-canvas/75 backdrop-blur-sm">
          <div className="w-[440px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-6 shadow-[0_24px_64px_rgba(0,0,0,0.12)]">
            <div className="flex items-baseline justify-between">
              <div className="text-[15px] font-medium tracking-[-0.01em] text-primary">
                {allSettled
                  ? failedCount === 0
                    ? `Saved ${doneCount} ${itemNoun}${doneCount === 1 ? "" : "s"}`
                    : `${doneCount}/${files.length} saved`
                  : `Reading ${files.length} ${itemNoun}${files.length === 1 ? "" : "s"}`}
              </div>
              {!busy && (
                <div className="font-mono text-[11px] text-tertiary">
                  {doneCount + failedCount}/{files.length}
                </div>
              )}
            </div>

            <ol className="mt-4 max-h-[280px] space-y-2 overflow-y-auto">
              {files.map((f) => (
                <li key={f.id} className="flex items-center gap-3">
                  <StatusIcon status={f.status} />
                  <div className="min-w-0 flex-1">
                    <div
                      className="truncate font-mono text-[12px] text-primary"
                      title={f.name}
                    >
                      {f.name}
                    </div>
                    <div
                      className={`font-mono text-[10px] ${
                        f.status === "failed" ? "text-red-600" : "text-tertiary"
                      }`}
                    >
                      {f.status === "failed"
                        ? f.error ?? "Failed"
                        : PHASE_LABEL[f.status]}
                    </div>
                  </div>
                </li>
              ))}
            </ol>

            {allSettled && (
              <button
                type="button"
                onClick={dismiss}
                className="mt-5 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
              >
                Close
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
}

function StatusIcon({ status }: { status: Status }) {
  if (status === "complete") {
    return (
      <span
        className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-accent text-inverse"
        aria-hidden
      >
        <svg width="9" height="9" viewBox="0 0 12 12" fill="none">
          <path
            d="M2.5 6.25L5 8.5L9.5 3.75"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
    );
  }
  if (status === "failed") {
    return (
      <span
        className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-red-600 text-inverse"
        aria-hidden
      >
        <svg width="8" height="8" viewBox="0 0 12 12" fill="none">
          <path
            d="M3 3l6 6M9 3l-6 6"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
        </svg>
      </span>
    );
  }
  if (status === "queued") {
    return (
      <span
        className="h-1.5 w-1.5 shrink-0 rounded-full bg-quaternary"
        aria-hidden
      />
    );
  }
  // Any active phase: animated spinner.
  return (
    <span
      className="flex h-4 w-4 shrink-0 items-center justify-center"
      aria-hidden
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
        className="animate-spin"
      >
        <circle
          cx="8"
          cy="8"
          r="6"
          stroke="currentColor"
          strokeWidth="1.6"
          className="text-border-strong"
          opacity="0.3"
        />
        <path
          d="M8 2a6 6 0 016 6"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          className="text-accent"
        />
      </svg>
    </span>
  );
}
