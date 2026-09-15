"use client";

import { useState } from "react";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";
import { useToast } from "@/components/ui/Toast";
import type {
  ImportPreviewResponse,
  ImportConfirmItem,
  ImportConfirmRequest,
  ConfirmItemAction,
  ImportConfirmResponse,
  ScanRegisterResponse,
  ScanStatusResponse,
} from "@/lib/types/gym";

interface EquipmentImportModalProps {
  locationId: string;
  locationName: string;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  bulkImportPreview: (rawText: string) => Promise<ImportPreviewResponse>;
  bulkImportConfirm: (body: ImportConfirmRequest) => Promise<ImportConfirmResponse>;
  // IMPL-GYM-003: when provided, a "Scan a video" source is offered. The detected
  // list flows into the same preview/confirm stages below.
  scanRegister?: (mimeType: string, sizeBytes: number) => Promise<ScanRegisterResponse>;
  scanStart?: (scanId: string) => Promise<ScanStatusResponse>;
  scanStatus?: (scanId: string) => Promise<ScanStatusResponse>;
  scanConfirm?: (scanId: string, body: ImportConfirmRequest) => Promise<ImportConfirmResponse>;
}

type Stage = "input" | "parsing" | "uploading" | "analyzing" | "preview" | "confirming" | "done";
type Source = "text" | "video";

type RowState = {
  selectedAction: ConfirmItemAction;
  nameOverride?: string;
};

// Client-enforced video guardrails (mirror the backend app.gym.video-scan limits).
const MAX_VIDEO_BYTES = 200 * 1024 * 1024;
const ACCEPTED_VIDEO = "video/mp4,video/quicktime";
const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_MS = 4 * 60 * 1000;

const SAMPLE_PLACEHOLDER = `Matrix treadmills [Certain].
Hampton round dumbbells ranging up to 100 lbs [Likely].
TKO EZ curl barbells 20, 30, 40, 50, 60, 70, 80, 90, 100, 110 lbs [Certain].
Matrix functional trainer cable machine [Certain].`;

function defaultActionFor(action: "MATCH_AUTO" | "MATCH_SUGGESTED" | "CREATE_NEW"): ConfirmItemAction {
  switch (action) {
    case "MATCH_AUTO":
    case "MATCH_SUGGESTED":
      return "USE_MATCH";
    case "CREATE_NEW":
      return "CREATE_NEW";
  }
}

function formatSpecs(specs: Record<string, unknown> | undefined | null): string {
  if (!specs) return '';
  const parts: string[] = [];
  const min = specs.minWeight, max = specs.maxWeight, inc = specs.increment;
  if (typeof min === 'number' && typeof max === 'number') {
    parts.push(`${min}–${max} lbs` + (typeof inc === 'number' ? ` (in ${inc} lb steps)` : ''));
  } else if (typeof max === 'number') {
    parts.push(`up to ${max} lbs`);
  }
  const weights = specs.weights;
  if (Array.isArray(weights) && weights.length > 0) {
    parts.push(`weights: ${weights.join(', ')} lbs`);
  }
  const handled = new Set(['minWeight', 'maxWeight', 'increment', 'weights']);
  for (const [k, v] of Object.entries(specs)) {
    if (handled.has(k)) continue;
    if (v == null || v === '' || v === false) continue;
    if (typeof v === 'object') continue;
    parts.push(`${k}: ${String(v)}`);
  }
  return parts.join(' · ');
}

function Spinner() {
  return (
    <div
      className="h-8 w-8 animate-spin rounded-full border-2 border-border-default border-t-accent"
      aria-label="Loading"
    />
  );
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function EquipmentImportModal({
  locationName,
  isOpen,
  onClose,
  onSuccess,
  bulkImportPreview,
  bulkImportConfirm,
  scanRegister,
  scanStart,
  scanStatus,
  scanConfirm,
}: EquipmentImportModalProps) {
  const toast = useToast();
  const [stage, setStage] = useState<Stage>("input");
  const [source, setSource] = useState<Source>("text");
  const [rawText, setRawText] = useState("");
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const [scanId, setScanId] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImportPreviewResponse | null>(null);
  const [rowStates, setRowStates] = useState<Record<number, RowState>>({});
  const [result, setResult] = useState<ImportConfirmResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const videoEnabled = Boolean(scanRegister && scanStart && scanStatus && scanConfirm);
  if (!isOpen) return null;

  function resetAndClose() {
    setStage("input");
    setSource("text");
    setRawText("");
    setVideoFile(null);
    setScanId(null);
    setPreview(null);
    setRowStates({});
    setResult(null);
    setError(null);
    onClose();
  }

  function seedPreview(previewResp: ImportPreviewResponse) {
    const states: Record<number, RowState> = {};
    for (const item of previewResp.items) {
      states[item.index] = { selectedAction: defaultActionFor(item.action) };
    }
    setPreview(previewResp);
    setRowStates(states);
    setStage("preview");
  }

  async function handleParse() {
    setError(null);
    setStage("parsing");
    try {
      seedPreview(await bulkImportPreview(rawText));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to parse equipment list");
      setStage("input");
    }
  }

  async function handleAnalyzeVideo() {
    if (!videoFile || !scanRegister || !scanStart || !scanStatus) return;
    setError(null);
    setStage("uploading");
    try {
      const reg = await scanRegister(videoFile.type, videoFile.size);
      // Upload the raw video DIRECTLY to the signed URL from the browser.
      const put = await fetch(reg.uploadUrl, {
        method: reg.method,
        headers: reg.headers,
        body: videoFile,
      });
      if (!put.ok) throw new Error(`Upload failed (${put.status})`);

      setScanId(reg.scanId);
      await scanStart(reg.scanId);
      setStage("analyzing");

      const deadline = Date.now() + POLL_TIMEOUT_MS;
      for (;;) {
        await sleep(POLL_INTERVAL_MS);
        const status = await scanStatus(reg.scanId);
        if (status.status === "READY" && status.preview) {
          seedPreview(status.preview);
          return;
        }
        if (status.status === "FAILED") {
          throw new Error(status.error ?? "We couldn't read equipment from that video.");
        }
        if (Date.now() > deadline) {
          throw new Error("Analysis is taking longer than expected. Try a shorter video.");
        }
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Video analysis failed");
      setStage("input");
    }
  }

  async function handleConfirm() {
    if (!preview) return;
    setError(null);
    setStage("confirming");
    try {
      const items: ImportConfirmItem[] = preview.items.map((item) => {
        const state = rowStates[item.index] ?? { selectedAction: defaultActionFor(item.action) };
        const confirmItem: ImportConfirmItem = { index: item.index, action: state.selectedAction };
        if (state.selectedAction === "USE_MATCH" && item.match) {
          confirmItem.matchedEquipmentId = item.match.equipmentId;
        }
        if (state.selectedAction === "CREATE_NEW") {
          confirmItem.parsed = item.parsed;
          if (state.nameOverride && state.nameOverride.trim() && state.nameOverride.trim() !== item.parsed.name) {
            confirmItem.overrides = { name: state.nameOverride.trim() };
          }
        }
        return confirmItem;
      });

      const resp =
        source === "video" && scanConfirm && scanId
          ? await scanConfirm(scanId, { items })
          : await bulkImportConfirm({ items });
      setResult(resp);
      setStage("done");
      toast.success("Import complete", {
        description: `${resp.addedToLocation} added · ${resp.created.length} new submissions`,
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : "Failed to import equipment";
      setError(message);
      setStage("preview");
      toast.error("Import failed", { description: message });
    }
  }

  function updateRow(index: number, patch: Partial<RowState>) {
    setRowStates((prev) => ({
      ...prev,
      [index]: { ...(prev[index] ?? { selectedAction: "SKIP" }), ...patch },
    }));
  }

  function handleDone() {
    onSuccess();
    resetAndClose();
  }

  function pickVideo(file: File | null) {
    setError(null);
    if (!file) {
      setVideoFile(null);
      return;
    }
    if (file.size > MAX_VIDEO_BYTES) {
      setError("That video is over 200 MB. Please record a shorter walkthrough.");
      return;
    }
    setVideoFile(file);
  }

  const nonSkipCount = preview
    ? preview.items.filter((item) => (rowStates[item.index]?.selectedAction ?? defaultActionFor(item.action)) !== "SKIP").length
    : 0;

  return (
    <ModalBackdrop
      onClose={resetAndClose}
      contentClassName="w-[720px] max-w-[95vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
    >
        {/* INPUT STAGE */}
        {stage === "input" && (
          <>
            <h2 className="mb-1 text-xl font-semibold text-primary">
              Add equipment to {locationName}
            </h2>

            {videoEnabled && (
              <div className="mb-4 mt-2 inline-flex rounded-md border border-border-default bg-canvas p-0.5">
                <button
                  type="button"
                  onClick={() => setSource("text")}
                  className={`cursor-pointer rounded px-3 py-1.5 text-sm font-medium ${source === "text" ? "bg-accent text-inverse" : "text-secondary hover:text-primary"}`}
                >
                  Paste a list
                </button>
                <button
                  type="button"
                  onClick={() => setSource("video")}
                  className={`cursor-pointer rounded px-3 py-1.5 text-sm font-medium ${source === "video" ? "bg-accent text-inverse" : "text-secondary hover:text-primary"}`}
                >
                  Scan a video
                </button>
              </div>
            )}

            {source === "text" && (
              <>
                <p className="mb-4 text-sm text-secondary">
                  Paste a list of equipment, one per line. We&apos;ll match it against the catalog
                  and create new submissions for anything we don&apos;t recognize.
                </p>
                <textarea
                  value={rawText}
                  onChange={(e) => setRawText(e.target.value)}
                  placeholder={SAMPLE_PLACEHOLDER}
                  className="min-h-[200px] w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary placeholder:text-tertiary focus:outline-none focus:ring-2 focus:ring-accent"
                />
              </>
            )}

            {source === "video" && (
              <>
                <p className="mb-4 text-sm text-secondary">
                  Upload a walkthrough video of the gym. We&apos;ll watch it and detect the
                  equipment for you to review — record a slow, steady pass with good lighting.
                </p>
                <label className="flex min-h-[160px] cursor-pointer flex-col items-center justify-center gap-2 rounded-md border border-dashed border-border-default bg-canvas px-3 py-6 text-center hover:bg-surface">
                  <input
                    type="file"
                    accept={ACCEPTED_VIDEO}
                    className="hidden"
                    onChange={(e) => pickVideo(e.target.files?.[0] ?? null)}
                  />
                  <span className="text-sm font-medium text-primary">
                    {videoFile ? videoFile.name : "Choose a video (MP4 or MOV, up to 200 MB)"}
                  </span>
                  {videoFile && (
                    <span className="text-xs text-tertiary">
                      {(videoFile.size / (1024 * 1024)).toFixed(1)} MB
                    </span>
                  )}
                </label>
              </>
            )}

            {error && (
              <p className="mt-3 rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-600">
                {error}
              </p>
            )}

            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                onClick={resetAndClose}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface"
              >
                Cancel
              </button>
              {source === "text" ? (
                <button
                  type="button"
                  onClick={handleParse}
                  disabled={rawText.trim().length === 0}
                  className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Parse
                </button>
              ) : (
                <button
                  type="button"
                  onClick={handleAnalyzeVideo}
                  disabled={!videoFile}
                  className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Analyze video
                </button>
              )}
            </div>
          </>
        )}

        {/* PARSING / UPLOADING / ANALYZING / CONFIRMING SPINNERS */}
        {(stage === "parsing" || stage === "uploading" || stage === "analyzing" || stage === "confirming") && (
          <div className="flex flex-col items-center justify-center gap-4 py-16">
            <Spinner />
            <p className="text-sm text-secondary">
              {stage === "parsing" && "Parsing equipment list..."}
              {stage === "uploading" && "Uploading video..."}
              {stage === "analyzing" && "Watching the video and detecting equipment..."}
              {stage === "confirming" && "Adding equipment..."}
            </p>
            {stage === "analyzing" && (
              <p className="text-xs text-tertiary">This can take a minute for a longer walkthrough.</p>
            )}
          </div>
        )}

        {/* PREVIEW STAGE */}
        {stage === "preview" && preview && (
          <>
            <h2 className="mb-1 text-xl font-semibold text-primary">Review equipment</h2>
            <p className="mb-4 text-sm text-secondary">
              Choose what to do with each detected item before adding it.
            </p>

            {/* Summary card */}
            <div className="mb-4 grid grid-cols-4 gap-3 rounded-md border border-border-default bg-canvas p-4">
              <div>
                <p className="text-xs text-tertiary">Total</p>
                <p className="text-lg font-semibold text-primary">{preview.summary.total}</p>
              </div>
              <div>
                <p className="text-xs text-tertiary">Matched</p>
                <p className="text-lg font-semibold text-primary">{preview.summary.matched}</p>
              </div>
              <div>
                <p className="text-xs text-tertiary">Suggested</p>
                <p className="text-lg font-semibold text-primary">{preview.summary.suggestedMatches}</p>
              </div>
              <div>
                <p className="text-xs text-tertiary">New</p>
                <p className="text-lg font-semibold text-primary">{preview.summary.newSubmissions}</p>
              </div>
            </div>

            {/* Rows */}
            <div className="space-y-2">
              {preview.items.map((item) => {
                const state = rowStates[item.index] ?? { selectedAction: defaultActionFor(item.action) };
                const canMatch = item.match !== null;
                return (
                  <div
                    key={item.index}
                    className="grid grid-cols-[1fr_140px_160px] items-start gap-3 rounded-md border border-border-default bg-canvas p-3"
                  >
                    {/* Column 1: name */}
                    <div className="min-w-0">
                      {state.selectedAction === "CREATE_NEW" ? (
                        <input
                          type="text"
                          value={state.nameOverride ?? item.parsed.name}
                          onChange={(e) => updateRow(item.index, { nameOverride: e.target.value })}
                          className="w-full rounded-md border border-border-default bg-surface px-2 py-1 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
                        />
                      ) : (
                        <p className="truncate text-sm font-medium text-primary">{item.parsed.name}</p>
                      )}
                      {formatSpecs(item.parsed.specs) && (
                        <div className="mt-0.5 text-[11px] text-tertiary">
                          {formatSpecs(item.parsed.specs)}
                        </div>
                      )}
                      {state.selectedAction === "USE_MATCH" && item.match && (
                        <p className="mt-1 text-xs text-secondary">
                          Matches: {item.match.name} ({Math.round(item.match.score * 100)}%)
                        </p>
                      )}
                    </div>

                    {/* Column 2: category / subcategory */}
                    <div className="text-xs text-tertiary">
                      <p className="truncate">
                        {item.parsed.category}
                        {item.parsed.confidence !== "CERTAIN" && (
                          <span className="text-secondary"> · {item.parsed.confidence}</span>
                        )}
                      </p>
                      <p className="truncate">{item.parsed.subcategory}</p>
                    </div>

                    {/* Column 3: action selector */}
                    <select
                      value={state.selectedAction}
                      onChange={(e) =>
                        updateRow(item.index, { selectedAction: e.target.value as ConfirmItemAction })
                      }
                      className="cursor-pointer rounded-md border border-border-default bg-surface px-2 py-1 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      {canMatch && <option value="USE_MATCH">Use match</option>}
                      <option value="CREATE_NEW">Create new</option>
                      <option value="SKIP">Skip</option>
                    </select>
                  </div>
                );
              })}
            </div>

            {error && (
              <p className="mt-3 rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-600">
                {error}
              </p>
            )}

            <div className="mt-6 flex justify-between gap-2">
              <button
                type="button"
                onClick={() => setStage("input")}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface"
              >
                Back
              </button>
              <button
                type="button"
                onClick={handleConfirm}
                disabled={nonSkipCount === 0}
                className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Add {nonSkipCount} item{nonSkipCount === 1 ? "" : "s"}
              </button>
            </div>
          </>
        )}

        {/* DONE STAGE */}
        {stage === "done" && result && (
          <>
            <h2 className="mb-4 text-xl font-semibold text-primary">Import complete</h2>
            <div className="space-y-2 rounded-md border border-border-default bg-canvas p-4">
              <p className="text-sm text-primary">
                Added <span className="font-semibold">{result.addedToLocation}</span> existing
                item{result.addedToLocation === 1 ? "" : "s"}
              </p>
              <p className="text-sm text-primary">
                Created <span className="font-semibold">{result.created.length}</span> new
                submission{result.created.length === 1 ? "" : "s"} (pending review)
              </p>
              <p className="text-sm text-primary">
                Skipped <span className="font-semibold">{result.skipped}</span>
              </p>
            </div>

            {result.failed.length > 0 && (
              <div className="mt-4 rounded-md border border-border-default bg-canvas p-3">
                <p className="text-[13px] font-medium text-primary">
                  {result.failed.length} item{result.failed.length === 1 ? '' : 's'} couldn&apos;t be imported:
                </p>
                <ul className="mt-2 space-y-1">
                  {result.failed.map((f) => (
                    <li key={f.index} className="text-[12px] text-secondary">
                      <span className="text-primary">{f.name}</span> — {f.reason}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="mt-6 flex justify-end">
              <button
                type="button"
                onClick={handleDone}
                className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90"
              >
                Done
              </button>
            </div>
          </>
        )}
    </ModalBackdrop>
  );
}
