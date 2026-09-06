"use client";

import { useState, useTransition } from "react";
import type {
  PatternReview,
  BlockParameters,
  ExerciseStrength,
} from "@/lib/types/progression";
import type { EnergyBalance } from "@/lib/types/plan";

// The progression console body (IMPL-PROG-01), ported from Android. Sections:
// "This week" (per-pattern volume trends), "Estimated strength" (per-lift e1RM —
// the weights), and "Training block" (the parameters + mode driving it).
// Presentational + interactive-for-mode-only; the page supplies the actions.

const MODES = ["GAINING", "RECOMP", "MAINTENANCE", "RECOVERY"] as const;

const MODE_INFO: Record<
  (typeof MODES)[number],
  { band: string; blurb: string }
> = {
  GAINING: {
    band: "Surplus (≥ +300 kcal/day)",
    blurb: "Pushes load up, highest weekly volume.",
  },
  RECOMP: {
    band: "Around maintenance (±300)",
    blurb: "Holds bodyweight, still adds load where you can.",
  },
  MAINTENANCE: {
    band: "Modest deficit (300–500)",
    blurb: "Holds load at lower effort (RIR), flat volume.",
  },
  RECOVERY: {
    band: "Large deficit (> 500) or recovery flag",
    blurb: "Eases load slightly, lowest volume to protect a hard cut.",
  },
};

type Tone = "good" | "alert" | "warn" | "neutral";
type ActiveGoal = { title: string; domain: string };

export function ProgressionConsole({
  weekReview,
  block,
  strength = [],
  goal = null,
  energy = null,
  onSelectMode,
}: {
  weekReview: PatternReview[];
  block: BlockParameters | null;
  strength?: ExerciseStrength[];
  // The goal this progression is serving (for the mode↔goal linkage).
  goal?: ActiveGoal | null;
  // The engine's measured energy state — shows measured vs. selected mode.
  energy?: EnergyBalance | null;
  onSelectMode?: (mode: string) => Promise<BlockParameters>;
}) {
  return (
    <div className="space-y-8">
      {/* ── This week ───────────────────────────────────────────── */}
      <section className="space-y-3">
        <SectionTitle>This week</SectionTitle>
        {weekReview.length === 0 ? (
          <EmptyState
            title="No trends yet"
            description="Log a few weeks of training to see per-pattern trends here."
          />
        ) : (
          <div className="space-y-3">
            {weekReview.map((review) => (
              <PatternReviewCard key={review.pattern} review={review} />
            ))}
          </div>
        )}
      </section>

      {/* ── Estimated strength (weights) ─────────────────────────── */}
      <section className="space-y-3">
        <SectionTitle>Estimated strength</SectionTitle>
        {strength.length === 0 ? (
          <EmptyState
            title="No strength estimates yet"
            description="Log working sets and the engine will estimate your 1-rep max per lift."
          />
        ) : (
          <StrengthCard strength={strength} />
        )}
      </section>

      {/* ── Training block ──────────────────────────────────────── */}
      <section className="space-y-3">
        <SectionTitle>Training block</SectionTitle>
        {block ? (
          <BlockParametersCard
            block={block}
            goal={goal}
            energy={energy}
            onSelectMode={onSelectMode}
          />
        ) : (
          <EmptyState
            title="No active block"
            description="Start a program to see the parameters driving your progression."
          />
        )}
      </section>
    </div>
  );
}

// ── This-week card ──────────────────────────────────────────────────

function PatternReviewCard({ review }: { review: PatternReview }) {
  const rising = review.proposedTarget > review.currentTarget;
  const falling = review.proposedTarget < review.currentTarget;
  return (
    <Card>
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-[15px] font-medium text-primary">
          {humanize(review.pattern)}
        </h3>
        <div className="flex flex-shrink-0 items-center gap-1.5">
          <Pill tone={trendTone(review.trend)}>{titleCase(review.trend)}</Pill>
          {review.deload && <Pill tone="warn">Deload</Pill>}
        </div>
      </div>

      <div className="mt-3 flex items-center gap-2">
        <CapsLabel>Sets</CapsLabel>
        <span className="font-mono text-[14px] text-tertiary">
          {review.currentTarget}
        </span>
        <span
          className={
            rising ? "text-good" : falling ? "text-alert" : "text-tertiary"
          }
        >
          {rising ? "↑" : falling ? "↓" : "→"}
        </span>
        <span className="font-mono text-[14px] font-medium text-primary">
          {review.proposedTarget}
        </span>
        <span className="ml-auto flex items-center gap-3 font-mono text-[11px] text-tertiary">
          <span title="Weekly volume slope">
            {formatSignedPct(review.weeklySlopePct)}
          </span>
          <span title="Fatigue index (0–1)">
            fatigue {review.fatigueIndex.toFixed(2)}
          </span>
        </span>
      </div>

      {review.reasoning.trim() && (
        <p className="mt-3 text-[13px] leading-relaxed text-secondary">
          {review.reasoning}
        </p>
      )}
    </Card>
  );
}

// ── Estimated-strength card (the weights) ───────────────────────────

function StrengthCard({ strength }: { strength: ExerciseStrength[] }) {
  return (
    <Card>
      <div className="divide-y divide-border-subtle">
        {strength.map((s) => (
          <div
            key={s.exerciseId}
            className="flex items-center justify-between gap-3 py-2.5 first:pt-0 last:pb-0"
          >
            <div className="min-w-0">
              <p className="truncate text-[14px] text-primary">{s.name}</p>
              {s.movementPattern && (
                <p className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                  {humanize(s.movementPattern)}
                </p>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-2.5">
              <span className="font-mono text-[15px] font-medium text-primary">
                {Math.round(s.e1rmLbs)}
                <span className="ml-0.5 text-[11px] font-normal text-tertiary">
                  lb
                </span>
              </span>
              <Pill tone={confidenceTone(s.confidence)}>
                {titleCase(s.confidence)}
              </Pill>
            </div>
          </div>
        ))}
      </div>
      <p className="mt-3 text-[11px] leading-relaxed text-tertiary">
        Estimated 1-rep max per lift — the engine&apos;s core belief, and what
        your prescribed working weights are derived from.
      </p>
    </Card>
  );
}

// ── Training-block card ─────────────────────────────────────────────

function BlockParametersCard({
  block,
  goal,
  energy,
  onSelectMode,
}: {
  block: BlockParameters;
  goal: ActiveGoal | null;
  energy: EnergyBalance | null;
  onSelectMode?: (mode: string) => Promise<BlockParameters>;
}) {
  const [mode, setMode] = useState(block.mode);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  function select(next: string) {
    if (next === mode || pending) return;
    const previous = mode;
    setMode(next); // optimistic
    setError(null);
    if (!onSelectMode) return; // preview: local-only
    startTransition(async () => {
      try {
        const updated = await onSelectMode(next);
        setMode(updated.mode);
      } catch (err) {
        setMode(previous); // revert
        setError(err instanceof Error ? err.message : "Update failed");
      }
    });
  }

  const repRanges = Object.entries(block.repRanges).sort(sortByKey);
  const ceilings = Object.entries(block.weeklyCeiling).sort(sortByKey);
  const rirCaps = Object.entries(block.rirCaps).sort(sortByKey);
  const measured = energy?.hasIntakeData ? energy.mode : null;
  const pinnedDiverges = block.manualOverride && measured != null && measured !== mode;

  return (
    <Card>
      <div className="space-y-5">
        {/* Mode selector — selected is evident; measured mode is marked. */}
        <div className="space-y-2.5">
          <div className="flex items-center justify-between gap-2">
            <CapsLabel>Mode</CapsLabel>
            <div className="flex items-center gap-1.5">
              {block.manualOverride && <Pill tone="neutral">Pinned</Pill>}
              {goal && (
                <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                  goal:{" "}
                  <span className="text-secondary">{goal.title}</span>
                </span>
              )}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-4">
            {MODES.map((value) => {
              const selected = value === mode;
              const isMeasured = measured === value;
              return (
                <button
                  key={value}
                  type="button"
                  disabled={pending}
                  onClick={() => select(value)}
                  className={[
                    "flex flex-col items-center gap-0.5 rounded-lg border px-2 py-2.5 transition-colors disabled:cursor-default",
                    selected
                      ? "border-accent bg-accent text-inverse shadow-[0_1px_6px_rgba(92,122,46,0.25)]"
                      : "border-border-default bg-surface text-secondary hover:border-accent/50",
                  ].join(" ")}
                >
                  <span className="flex items-center gap-1 caps-mono text-[10px]">
                    {selected && (
                      <i className="ti ti-check text-[11px]" aria-hidden />
                    )}
                    {titleCase(value)}
                  </span>
                  {isMeasured && (
                    <span
                      className={`caps-mono text-[8px] tracking-[0.06em] ${selected ? "text-inverse/80" : "text-accent-dim"}`}
                    >
                      measured
                    </span>
                  )}
                </button>
              );
            })}
          </div>

          {error && <p className="font-mono text-[11px] text-alert">{error}</p>}
          {pinnedDiverges && measured && (
            <p className="flex items-center gap-1.5 text-[11px] text-warn">
              <i className="ti ti-pin text-[12px]" aria-hidden />
              Pinned to {titleCase(mode)} — your recent eating measures as{" "}
              {titleCase(measured)}.
            </p>
          )}

          {/* What the mode means + how it links to eating and the goal. */}
          {isKnownMode(mode) && (
            <div className="space-y-1.5 rounded-lg bg-canvas-muted px-3 py-2.5">
              <p className="text-[12px] leading-relaxed text-secondary">
                <span className="font-medium text-primary">
                  {titleCase(mode)}
                </span>{" "}
                — {MODE_INFO[mode].blurb}{" "}
                <span className="text-tertiary">({MODE_INFO[mode].band})</span>
              </p>
              {energy?.hasIntakeData && (
                <p className="text-[11px] leading-relaxed text-tertiary">
                  <span className="text-secondary">Measured:</span> eating ~
                  {fmt(energy.meanIntakeKcal)} vs ~{fmt(energy.maintenanceKcal)}{" "}
                  maintenance → {titleCase(energy.mode)}.
                </p>
              )}
              {goal && (
                <p className="text-[11px] leading-relaxed text-tertiary">
                  <span className="text-secondary">Goal:</span> {goal.title} (
                  {humanize(goal.domain)}) — {goalIntent(goal.domain)}
                </p>
              )}
              <p className="text-[11px] leading-relaxed text-tertiary">
                Mode is chosen automatically from your energy balance and applies
                across every program. Pin one to override.
              </p>
            </div>
          )}
        </div>

        {/* Block targets — hairlined so they parse as a table. */}
        <KeyValueGroup label="Block targets">
          <KeyValueRow
            label="Success criterion"
            value={titleCase(block.successCriterion)}
          />
          <KeyValueRow
            label="Expected load trend"
            value={formatLoadTrend(block.expectedDriftPerDay)}
          />
        </KeyValueGroup>

        {repRanges.length > 0 && (
          <KeyValueGroup label="Rep ranges">
            {repRanges.map(([pattern, range]) => (
              <KeyValueRow
                key={pattern}
                label={humanize(pattern)}
                value={`${range[0]}–${range[range.length - 1]}`}
              />
            ))}
          </KeyValueGroup>
        )}

        {ceilings.length > 0 && (
          <KeyValueGroup label="Weekly set ceiling">
            {ceilings.map(([pattern, ceiling]) => (
              <KeyValueRow
                key={pattern}
                label={humanize(pattern)}
                value={`${ceiling} sets`}
              />
            ))}
          </KeyValueGroup>
        )}

        {rirCaps.length > 0 && (
          <KeyValueGroup label="RIR caps">
            {rirCaps.map(([klass, cap]) => (
              <KeyValueRow
                key={klass}
                label={titleCase(klass)}
                value={trimNumber(cap)}
              />
            ))}
          </KeyValueGroup>
        )}
      </div>
    </Card>
  );
}

// ── Small building blocks ───────────────────────────────────────────

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
      {children}
    </div>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="caps-mono text-[11px] tracking-[0.06em] text-tertiary">
      {children}
    </h2>
  );
}

function CapsLabel({ children }: { children: React.ReactNode }) {
  return (
    <span className="caps-mono text-[10px] tracking-[0.06em] text-tertiary">
      {children}
    </span>
  );
}

function KeyValueGroup({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1">
      <CapsLabel>{label}</CapsLabel>
      <div className="divide-y divide-border-subtle">{children}</div>
    </div>
  );
}

function KeyValueRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5">
      <span className="text-[13px] text-secondary">{label}</span>
      <span className="font-mono text-[13px] text-primary">{value}</span>
    </div>
  );
}

const TONE_CLASSES: Record<Tone, string> = {
  good: "bg-good-bg text-good",
  alert: "bg-alert-bg text-alert",
  warn: "bg-warn-bg text-warn",
  neutral: "bg-canvas-muted text-neutral",
};

function Pill({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return (
    <span
      className={`rounded-full px-2 py-0.5 caps-mono text-[9px] tracking-[0.06em] ${TONE_CLASSES[tone]}`}
    >
      {children}
    </span>
  );
}

function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-dashed border-border-default bg-surface px-5 py-8 text-center">
      <p className="text-[14px] font-medium text-primary">{title}</p>
      <p className="mt-1 text-[13px] text-secondary">{description}</p>
    </div>
  );
}

// ── Formatting ──────────────────────────────────────────────────────

function isKnownMode(mode: string): mode is (typeof MODES)[number] {
  return (MODES as readonly string[]).includes(mode);
}

function trendTone(trend: string): Tone {
  switch (trend.toUpperCase()) {
    case "RISING":
      return "good";
    case "FALLING":
      return "alert";
    default:
      return "neutral";
  }
}

function confidenceTone(confidence: string): Tone {
  switch (confidence.toUpperCase()) {
    case "HIGH":
      return "good";
    case "LOW":
      return "warn";
    default:
      return "neutral";
  }
}

// What a goal domain implies for the training/energy direction.
function goalIntent(domain: string): string {
  switch (domain.toUpperCase()) {
    case "BODY_COMPOSITION":
      return "fat loss favors a deficit (Maintenance / Recovery).";
    case "STRENGTH":
      return "strength favors eating at or above maintenance (Recomp / Gaining).";
    case "METABOLIC":
      return "metabolic health usually favors maintenance.";
    default:
      return "sets the intent your eating should serve.";
  }
}

// Acronyms that should stay upper-cased rather than title-cased.
const ACRONYMS = new Set(["RIR", "RPE", "1RM"]);

// "PUSH_HORIZONTAL" → "Push Horizontal"; "…_LOWER_RIR" → "… Lower RIR".
function humanize(enumName: string): string {
  return enumName
    .split("_")
    .filter(Boolean)
    .map((w) =>
      ACRONYMS.has(w.toUpperCase())
        ? w.toUpperCase()
        : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase(),
    )
    .join(" ");
}

const titleCase = humanize;

// The e1RM drift the block expects, as a per-week load trend (backend carries
// it as lb/day). This is the block-level "weight" signal.
function formatLoadTrend(driftPerDay: number): string {
  const perWeek = driftPerDay * 7;
  if (Math.abs(perWeek) < 0.05) return "Holding — no planned change";
  const sign = perWeek > 0 ? "+" : "−";
  return `${sign}${Math.abs(perWeek).toFixed(1)} lb / week`;
}

function formatSignedPct(v: number): string {
  const sign = v > 0 ? "+" : "";
  return `${sign}${trimNumber(v)}%`;
}

function trimNumber(v: number): string {
  return Number.isInteger(v) ? String(v) : String(v);
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

function sortByKey(a: [string, unknown], b: [string, unknown]): number {
  return a[0].localeCompare(b[0]);
}
