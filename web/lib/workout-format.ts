// Pure formatting helpers for workout program prescriptions. Import-safe from
// client and server (no apiFetch). Renders a prescription row as the canonical
// "3 × 8–10 @ RPE 8 · rest 90s" string.

import type {
  Prescription,
  Intensity,
  ScheduledWorkoutResponse,
} from "./types/workout-program";

function formatIntensity(intensity: Intensity | null): string | null {
  if (!intensity || intensity.kind === "NONE" || intensity.value == null) return null;
  if (intensity.kind === "RPE") return `RPE ${intensity.value}`;
  if (intensity.kind === "PERCENT_1RM") return `${intensity.value}% 1RM`;
  return null;
}

function formatReps(p: Prescription): string | null {
  if (p.durationSeconds != null) {
    return formatDuration(p.durationSeconds);
  }
  if (p.repsMin != null && p.repsMax != null) {
    return p.repsMin === p.repsMax ? `${p.repsMin}` : `${p.repsMin}–${p.repsMax}`;
  }
  if (p.repsMin != null) return `${p.repsMin}`;
  if (p.repsMax != null) return `${p.repsMax}`;
  return null;
}

export function formatDuration(seconds: number): string {
  if (seconds % 60 === 0) return `${seconds / 60} min`;
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}m ${s}s`;
}

// "3 × 8–10 @ RPE 8 · rest 90s"
export function formatPrescription(p: Prescription): string {
  const parts: string[] = [];
  const reps = formatReps(p);
  if (p.sets != null && reps) parts.push(`${p.sets} × ${reps}`);
  else if (p.sets != null) parts.push(`${p.sets} sets`);
  else if (reps) parts.push(reps);

  const intensity = formatIntensity(p.intensity);
  let main = parts.join(" ");
  if (intensity) main = main ? `${main} @ ${intensity}` : intensity;

  const tail: string[] = [];
  if (p.restSeconds != null) tail.push(`rest ${formatRest(p.restSeconds)}`);
  if (p.tempo) tail.push(`tempo ${p.tempo}`);

  return [main, ...tail].filter(Boolean).join(" · ");
}

function formatRest(seconds: number): string {
  if (seconds >= 60 && seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}

// Renders the sets a user actually performed, e.g. "135 × 8 · 135 × 8 · 155 × 6"
// (lb), or just the weights when the export carried no reps ("95 · 100 · 100 lb").
// Null when nothing was logged (a plan template, not a performed session).
export function formatLoggedSets(p: Prescription): string | null {
  const sets = p.loggedSets;
  if (!sets || sets.length === 0) return null;
  const anyReps = sets.some((s) => s.reps != null);
  const anyWeight = sets.some((s) => s.weightLbs != null);
  const parts = sets.map((s) => {
    const w = s.weightLbs != null ? `${trimWeight(s.weightLbs)}` : anyWeight ? "BW" : "";
    if (s.reps != null) return w ? `${w} × ${s.reps}` : `${s.reps}`;
    return w;
  });
  const body = parts.filter(Boolean).join(" · ");
  if (!body) return `${sets.length} set${sets.length === 1 ? "" : "s"}`;
  return anyWeight && !anyReps ? `${body} lb` : body;
}

function trimWeight(w: number): string {
  return Number.isInteger(w) ? `${w}` : `${w}`.replace(/\.0+$/, "");
}

// Total number of performed sets across every exercise in a session.
export function totalLoggedSets(session: ScheduledWorkoutResponse): number {
  let n = 0;
  for (const block of session.session.blocks) {
    for (const p of block.prescriptions) n += p.loggedSets?.length ?? 0;
  }
  return n;
}

export function formatDeloadModifier(p: Prescription): string | null {
  const d = p.deloadModifier;
  if (!d) return null;
  const parts: string[] = [];
  if (d.setsMultiplier != null) parts.push(`×${d.setsMultiplier} sets`);
  if (d.intensityDelta != null) {
    const sign = d.intensityDelta > 0 ? "+" : "";
    parts.push(`${sign}${d.intensityDelta} intensity`);
  }
  return parts.length ? `Deload: ${parts.join(", ")}` : null;
}
