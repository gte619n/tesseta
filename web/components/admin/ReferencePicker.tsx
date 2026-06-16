"use client";

import { useMemo, useState } from "react";
import { useToast } from "@/components/ui/Toast";
import type { ExerciseResponse } from "@/lib/types/exercise";
import { thumbUrl } from "@/lib/exercise-thumb";

// IMPL-20: unified grounding picker. Shows the exercise's own candidate images
// (demoFrames[].imageCandidates) AND its external reference.images, each as a
// toggleable thumbnail. Selection is pre-seeded from groundingImageUrls and
// persisted via saveGrounding (PUT /grounding). The current selection is
// surfaced to the parent (the regen modal) so it can be sent as
// referenceImageUrls.

export type ReferenceCandidate = {
  url: string;
  origin: "own" | "external";
  // Human label for grouping (frame label or source name).
  group: string;
};

// Collect every candidate URL the admin can ground on, de-duplicated, keeping
// the first origin/group seen for each.
export function collectCandidates(
  exercise: ExerciseResponse,
): ReferenceCandidate[] {
  const seen = new Set<string>();
  const out: ReferenceCandidate[] = [];
  for (const frame of exercise.demoFrames) {
    for (const url of frame.imageCandidates) {
      if (!url || seen.has(url)) continue;
      seen.add(url);
      out.push({ url, origin: "own", group: frame.label || frame.key });
    }
  }
  const ref = exercise.reference;
  if (ref?.images) {
    for (const url of ref.images) {
      if (!url || seen.has(url)) continue;
      seen.add(url);
      out.push({ url, origin: "external", group: ref.source || "reference" });
    }
  }
  return out;
}

interface Props {
  exercise: ExerciseResponse;
  // Persist the selection (PUT /grounding). Returns the updated exercise.
  saveGrounding: (exerciseId: string, imageUrls: string[]) => Promise<void>;
  // Notify the parent of the live selection (so a regen can use it without a
  // round trip). Called on every toggle and after save.
  onSelectionChange?: (selected: string[]) => void;
}

export function ReferencePicker({
  exercise,
  saveGrounding,
  onSelectionChange,
}: Props) {
  const toast = useToast();
  const candidates = useMemo(() => collectCandidates(exercise), [exercise]);

  // Pre-select from the persisted grounding set, intersected with what's
  // actually available (a stored URL whose candidate was later deleted just
  // drops out of the visible toggles but stays in the saved set until re-save).
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(exercise.groundingImageUrls),
  );
  const [saving, setSaving] = useState(false);

  function toggle(url: string) {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(url)) next.delete(url);
      else next.add(url);
      onSelectionChange?.([...next]);
      return next;
    });
  }

  async function handleSave() {
    setSaving(true);
    try {
      const urls = [...selected];
      await saveGrounding(exercise.exerciseId, urls);
      onSelectionChange?.(urls);
      toast.success("Grounding images saved");
    } catch (e) {
      toast.error("Failed to save grounding", {
        description: e instanceof Error ? e.message : "Unknown error",
      });
    } finally {
      setSaving(false);
    }
  }

  if (candidates.length === 0) {
    return (
      <p className="text-xs text-tertiary">
        No candidate or reference images to ground on yet.
      </p>
    );
  }

  const own = candidates.filter((c) => c.origin === "own");
  const external = candidates.filter((c) => c.origin === "external");

  return (
    <div className="space-y-3">
      {own.length > 0 ? (
        <Section title="Own candidates">
          {own.map((c) => (
            <CandidateThumb
              key={c.url}
              candidate={c}
              selected={selected.has(c.url)}
              onToggle={() => toggle(c.url)}
            />
          ))}
        </Section>
      ) : null}

      {external.length > 0 ? (
        <Section title="External references (grounding only)">
          {external.map((c) => (
            <CandidateThumb
              key={c.url}
              candidate={c}
              selected={selected.has(c.url)}
              onToggle={() => toggle(c.url)}
            />
          ))}
        </Section>
      ) : null}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={handleSave}
          disabled={saving}
          className="cursor-pointer rounded border border-border-default bg-surface px-2.5 py-1 text-[11px] font-medium text-primary hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? "Saving…" : "Save grounding set"}
        </button>
        <span className="text-[11px] text-tertiary">
          {selected.size} selected
        </span>
      </div>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
        {title}
      </span>
      <div className="mt-1.5 flex flex-wrap gap-2">{children}</div>
    </div>
  );
}

function CandidateThumb({
  candidate,
  selected,
  onToggle,
}: {
  candidate: ReferenceCandidate;
  selected: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={selected}
      title={candidate.group}
      className={
        "relative block h-20 w-16 overflow-hidden rounded border p-0 " +
        (selected
          ? "border-accent ring-2 ring-accent"
          : "border-border-default opacity-60 hover:opacity-100")
      }
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={thumbUrl(candidate.url)}
        alt={candidate.group}
        loading="lazy"
        onError={(e) => {
          const img = e.currentTarget;
          if (img.src !== candidate.url) img.src = candidate.url;
        }}
        className="h-full w-full object-cover"
      />
      {selected ? (
        <span className="absolute right-0.5 top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-accent text-[10px] leading-none text-inverse">
          ✓
        </span>
      ) : null}
    </button>
  );
}
