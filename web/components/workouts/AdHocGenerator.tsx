"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type {
  AdHocDraft,
  GenerateAdHocRequest,
  GenerateAdHocResponse,
} from "@/lib/types/adhoc";
import { formatDuration } from "@/lib/workout-format";

const PRESETS: { id: string; label: string }[] = [
  { id: "hotel-gym", label: "Hotel gym" },
  { id: "home", label: "Home" },
  { id: "bodyweight", label: "Bodyweight only" },
  { id: "full-gym", label: "Full gym" },
];

type GenerateAction = (
  body: GenerateAdHocRequest,
) => Promise<GenerateAdHocResponse>;
type SaveAction = (draft: AdHocDraft, prompt: string) => Promise<string>;

// Prompt → preview (editable-later) → save. Mirrors the nutrition Adjust-with-AI
// preview→confirm flow (IMPL-ADHOC-01 D14). Generation + save run as server
// actions passed from the page so the browser never holds backend credentials.
export function AdHocGenerator({
  generateAction,
  saveAction,
}: {
  generateAction: GenerateAction;
  saveAction: SaveAction;
}) {
  const router = useRouter();
  const [prompt, setPrompt] = useState("");
  const [minutes, setMinutes] = useState<number | "">("");
  const [presetId, setPresetId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<GenerateAdHocResponse | null>(null);

  async function onGenerate() {
    if (!prompt.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const res = await generateAction({
        prompt: prompt.trim(),
        targetDurationMinutes: minutes === "" ? null : Number(minutes),
        presetId,
        freeText: presetId ? null : prompt.trim(),
      });
      setResult(res);
    } catch (e) {
      setError(
        e instanceof Error && /503/.test(e.message)
          ? "Workout generation isn't enabled on this server yet."
          : "Couldn't generate a workout. Try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    if (!result) return;
    setBusy(true);
    setError(null);
    try {
      await saveAction(result.draft, prompt.trim());
      // The template detail/run screen is a follow-up (web's live player is
      // deferred), so land back on the library where the new card appears.
      router.push("/me/workouts/library");
    } catch {
      setError("Couldn't save the workout.");
      setBusy(false);
    }
  }

  return (
    <div className="space-y-5">
      {!result && (
        <div className="space-y-4">
          <div>
            <label className="text-[13px] font-medium text-primary">
              Describe your workout
            </label>
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={3}
              placeholder="e.g. A 45-minute full-body workout, bodyweight and one 35 lb kettlebell"
              className="mt-1.5 w-full rounded-[10px] border-[0.5px] border-border-default bg-surface px-3 py-2 text-[14px] text-primary outline-none focus:border-accent"
            />
          </div>

          <div>
            <span className="text-[13px] font-medium text-primary">Where?</span>
            <div className="mt-1.5 flex flex-wrap gap-2">
              {PRESETS.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => setPresetId(presetId === p.id ? null : p.id)}
                  className={`rounded-full border-[0.5px] px-3 py-1 text-[12px] ${
                    presetId === p.id
                      ? "border-accent bg-accent/10 text-accent"
                      : "border-border-default text-secondary hover:text-primary"
                  }`}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="text-[13px] font-medium text-primary">
              Time budget (minutes)
            </label>
            <input
              type="number"
              min={5}
              max={180}
              value={minutes}
              onChange={(e) =>
                setMinutes(e.target.value === "" ? "" : Number(e.target.value))
              }
              placeholder="30"
              className="mt-1.5 block w-32 rounded-[10px] border-[0.5px] border-border-default bg-surface px-3 py-2 text-[14px] text-primary outline-none focus:border-accent"
            />
          </div>

          {error && <p className="text-[13px] text-red-500">{error}</p>}

          <button
            type="button"
            disabled={busy || !prompt.trim()}
            onClick={onGenerate}
            className="rounded-full bg-accent px-5 py-2 text-[14px] font-medium text-white disabled:opacity-50"
          >
            {busy ? "Generating…" : "Generate"}
          </button>
        </div>
      )}

      {result && (
        <div className="space-y-4">
          <DraftPreview draft={result.draft} />

          {result.constraintReport.length > 0 && (
            <div className="rounded-[10px] border-[0.5px] border-amber-500/40 bg-amber-500/5 px-3 py-2 text-[12px] text-amber-700">
              <p className="font-medium">Some exercises were adjusted for your equipment:</p>
              <ul className="mt-1 list-disc pl-4">
                {result.constraintReport.map((v, i) => (
                  <li key={i}>
                    {v.exerciseId}: {v.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {error && <p className="text-[13px] text-red-500">{error}</p>}

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy}
              onClick={onSave}
              className="rounded-full bg-accent px-5 py-2 text-[14px] font-medium text-white disabled:opacity-50"
            >
              {busy ? "Saving…" : "Save to library"}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setResult(null);
                setError(null);
              }}
              className="rounded-full border-[0.5px] border-border-default px-5 py-2 text-[14px] text-secondary hover:text-primary disabled:opacity-50"
            >
              Regenerate
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function DraftPreview({ draft }: { draft: AdHocDraft }) {
  const est = draft.estimatedDurationSeconds
    ? formatDuration(draft.estimatedDurationSeconds)
    : null;
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
      <h2 className="text-[16px] font-semibold text-primary">{draft.title}</h2>
      {draft.summary && (
        <p className="mt-0.5 text-[13px] text-secondary">{draft.summary}</p>
      )}
      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 text-[11px] text-tertiary">
        {est && <span>~{est}</span>}
        {draft.equipmentContext?.label && <span>· {draft.equipmentContext.label}</span>}
        {draft.tags.map((t) => (
          <span key={t} className="rounded-full bg-canvas px-2 py-0.5">
            {t}
          </span>
        ))}
      </div>
      <div className="mt-3 space-y-3">
        {(draft.day?.blocks ?? []).map((b) => (
          <div key={b.blockId}>
            <p className="text-[12px] font-medium uppercase tracking-wide text-tertiary">
              {b.title || b.type}
            </p>
            <ul className="mt-1 space-y-0.5">
              {b.prescriptions.map((rx, i) => (
                <li key={i} className="text-[13px] text-primary">
                  {rx.exercise?.name ?? rx.exerciseId}
                  <span className="text-secondary">
                    {" — "}
                    {rx.durationSeconds
                      ? `${rx.durationSeconds}s`
                      : `${rx.sets ?? 3} × ${rx.repsMin ?? ""}${
                          rx.repsMax && rx.repsMax !== rx.repsMin ? `–${rx.repsMax}` : ""
                        }`}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}
