"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

// Dashed-border tile + full-page drop overlay + multi-step progress
// overlay. The actual upload goes to /api/dexa/upload (a Next.js route
// handler that proxies to the backend with auth), which streams back
// SSE events {phase, message?, scan?, error?}.
//
// Phases (mirror the backend contract in DexaScanController):
//   uploading  → "Saving your PDF"
//   extracting → "Reading the scan with Gemini"
//   saving     → "Storing the results"
//   complete   → done, payload includes the saved scan
//   failed     → fatal, payload includes an error message

type Phase = "uploading" | "extracting" | "saving" | "complete";
type Status =
  | { kind: "idle" }
  | { kind: "running"; phase: Phase }
  | { kind: "done" }
  | { kind: "failed"; error: string };

const STEPS: { id: Phase; label: string }[] = [
  { id: "uploading", label: "Saving your PDF" },
  { id: "extracting", label: "Reading the scan with Gemini" },
  { id: "saving", label: "Storing the results" },
];

export function DexaUploadButton() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [status, setStatus] = useState<Status>({ kind: "idle" });
  const dragDepth = useRef(0);

  const busy = status.kind === "running";

  async function submit(file: File) {
    if (!file.name.toLowerCase().endsWith(".pdf") && file.type !== "application/pdf") {
      setStatus({ kind: "failed", error: "Drop a PDF file." });
      return;
    }
    setStatus({ kind: "running", phase: "uploading" });
    const fd = new FormData();
    fd.set("file", file);

    let res: Response;
    try {
      res = await fetch("/api/dexa/upload", { method: "POST", body: fd });
    } catch (err) {
      setStatus({
        kind: "failed",
        error: err instanceof Error ? err.message : "Network error",
      });
      return;
    }
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => "");
      setStatus({
        kind: "failed",
        error: text || `Upload failed (${res.status})`,
      });
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let succeeded = false;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE events are separated by a blank line. Each event's payload
      // is the concatenation of its `data:` lines.
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) !== -1) {
        const raw = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        const data = raw
          .split("\n")
          .filter((l) => l.startsWith("data:"))
          .map((l) => l.slice(5).trimStart())
          .join("\n");
        if (!data) continue;
        try {
          const event = JSON.parse(data) as
            | { phase: Phase; message?: string }
            | { phase: "complete"; scan: unknown }
            | { phase: "failed"; error: string };
          if (event.phase === "complete") {
            succeeded = true;
            setStatus({ kind: "done" });
          } else if (event.phase === "failed") {
            setStatus({
              kind: "failed",
              error: (event as { error: string }).error,
            });
          } else {
            setStatus({ kind: "running", phase: event.phase });
          }
        } catch {
          // Skip malformed events — better to keep the stream alive.
        }
      }
    }
    if (succeeded) {
      // Refresh the page so the new scan appears in the table. Hold
      // the success overlay for a beat so the user sees confirmation.
      router.refresh();
      setTimeout(() => setStatus({ kind: "idle" }), 900);
    }
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
      const file = e.dataTransfer.files[0];
      if (file) submit(file);
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
    const file = e.target.files?.[0];
    if (inputRef.current) inputRef.current.value = "";
    if (file) submit(file);
  }

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
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
        title="Drag a DEXA PDF onto the page, or click to choose"
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
        <span>Drop DEXA PDF</span>
      </button>

      {dragOver && status.kind === "idle" && (
        <div className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center bg-canvas/85 backdrop-blur-sm">
          <div className="rounded-[14px] border-2 border-dashed border-accent bg-surface px-10 py-8 text-center shadow-[0_24px_64px_rgba(0,0,0,0.12)]">
            <div className="text-[22px] font-medium tracking-[-0.015em] text-primary">
              Drop your DEXA PDF
            </div>
            <div className="mt-2 font-mono text-[12px] text-tertiary">
              We&apos;ll extract the scan and add it to your readings.
            </div>
          </div>
        </div>
      )}

      {(status.kind === "running" ||
        status.kind === "done" ||
        status.kind === "failed") && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-canvas/75 backdrop-blur-sm">
          <div className="w-[400px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-6 shadow-[0_24px_64px_rgba(0,0,0,0.12)]">
            <div className="text-[15px] font-medium tracking-[-0.01em] text-primary">
              {status.kind === "done"
                ? "Scan saved"
                : status.kind === "failed"
                ? "Couldn’t read your scan"
                : "Reading your DEXA scan"}
            </div>
            {status.kind === "failed" ? (
              <>
                <div className="mt-3 font-mono text-[12px] leading-[1.5] text-secondary">
                  {status.error}
                </div>
                <button
                  type="button"
                  onClick={() => setStatus({ kind: "idle" })}
                  className="mt-5 cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary"
                >
                  Dismiss
                </button>
              </>
            ) : (
              <ol className="mt-4 space-y-2.5">
                {STEPS.map((step, idx) => {
                  const currentIdx =
                    status.kind === "running"
                      ? STEPS.findIndex((s) => s.id === status.phase)
                      : STEPS.length; // done → all complete
                  const state =
                    idx < currentIdx
                      ? "done"
                      : idx === currentIdx
                      ? "active"
                      : "pending";
                  return (
                    <li key={step.id} className="flex items-center gap-3">
                      <StepIcon state={state} />
                      <span
                        className={
                          state === "pending"
                            ? "font-mono text-[12px] text-quaternary"
                            : state === "active"
                            ? "font-mono text-[12px] text-primary"
                            : "font-mono text-[12px] text-secondary"
                        }
                      >
                        {step.label}
                      </span>
                    </li>
                  );
                })}
              </ol>
            )}
          </div>
        </div>
      )}
    </>
  );
}

function StepIcon({ state }: { state: "pending" | "active" | "done" }) {
  if (state === "done") {
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
  if (state === "active") {
    return (
      <span
        className="flex h-4 w-4 shrink-0 items-center justify-center"
        aria-hidden
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" className="animate-spin">
          <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.6" className="text-border-strong" opacity="0.3" />
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
  return (
    <span
      className="h-1.5 w-1.5 shrink-0 rounded-full bg-quaternary"
      aria-hidden
    />
  );
}
