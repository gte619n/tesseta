"use client";

import { useState, useTransition } from "react";
import type { PlanChain, PlanFlag, PlanActionKind } from "@/lib/types/plan";
import { computeFlags, kcalOf } from "@/lib/plan-coherence";

// The plan-coherence body (IMPL-PLAN-01): a top-to-bottom view of the chain —
// Goal → Program/phase → calorie target → today's intake → engine energy state
// — followed by the coherence flags (divergences) with one-tap reconcile.
// Presentational; the page supplies the reconcile action.

export function PlanCoherence({
  chain,
  onReconcile,
}: {
  chain: PlanChain;
  // Runs a reconcile action and resolves when the server has applied it. Omitted
  // in the preview harness (buttons then no-op with a transient "Done").
  onReconcile?: (kind: PlanActionKind) => Promise<void>;
}) {
  const flags = computeFlags(chain);
  const targetKcal = kcalOf(chain.target);
  const intakeKcal = kcalOf(chain.today?.totals ?? null);

  return (
    <div className="space-y-8">
      {/* ── Coherence flags ─────────────────────────────────────── */}
      <section className="space-y-3">
        <SectionTitle>Coherence</SectionTitle>
        <div className="overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface">
          <CoherenceHeader flags={flags} />
          {flags.length > 0 && (
            <div className="divide-y divide-border-subtle">
              {flags.map((f) => (
                <FlagRow key={f.id} flag={f} onReconcile={onReconcile} />
              ))}
            </div>
          )}
        </div>
      </section>

      {/* ── The chain ───────────────────────────────────────────── */}
      <section className="space-y-3">
        <SectionTitle>The chain</SectionTitle>
        <div className="space-y-2">
          <ChainRow
            label="Goal"
            value={chain.goal?.title ?? "None"}
            sub={chain.goal ? titleCase(chain.goal.domain) : undefined}
            muted={!chain.goal}
          />
          <ChainRow
            label="Program"
            value={chain.program?.title ?? "None"}
            sub={
              chain.program?.phaseTitle
                ? `Phase: ${chain.program.phaseTitle}`
                : undefined
            }
            warn={!!chain.goal && !!chain.program && chain.program.goalId !== chain.goal.id}
            muted={!chain.program}
          />
          <ChainRow
            label="Calorie target"
            value={targetKcal != null ? `${fmt(targetKcal)} kcal` : "Not set"}
            sub={macroLine(chain.target)}
            muted={targetKcal == null}
          />
          <ChainRow
            label="Today's intake"
            value={
              intakeKcal != null ? `${fmt(intakeKcal)} kcal` : "Nothing logged"
            }
            sub={
              targetKcal != null && intakeKcal != null
                ? `${fmt(Math.max(0, targetKcal - intakeKcal))} kcal left`
                : undefined
            }
            muted={intakeKcal == null}
          />
          <ChainRow
            label="Engine · maintenance"
            value={
              chain.energy?.hasIntakeData
                ? `~${fmt(chain.energy.maintenanceKcal)} kcal`
                : "Warming up"
            }
            sub={
              chain.energy?.hasIntakeData
                ? `Eating ~${fmt(chain.energy.meanIntakeKcal)} · ${describeBalance(chain.energy.balanceKcal)}`
                : "Needs more logged days to measure"
            }
            muted={!chain.energy?.hasIntakeData}
          />
          <ChainRow
            label="Engine · mode"
            value={chain.block ? titleCase(chain.block.mode) : "—"}
            sub={chain.block?.manualOverride ? "Pinned manually" : "Auto"}
            muted={!chain.block}
          />
        </div>
      </section>
    </div>
  );
}

// ── Flag card ───────────────────────────────────────────────────────

// The card's status strip: a single glanceable summary of how many things need
// reconciling, so the block reads as one unit rather than a loose list.
function CoherenceHeader({ flags }: { flags: PlanFlag[] }) {
  const actionable = flags.filter((f) => f.severity !== "info").length;
  const synced = actionable === 0;
  const title = synced
    ? "In sync"
    : `${actionable} ${actionable === 1 ? "thing" : "things"} to reconcile`;
  const sub = synced
    ? "Your goal, program, calorie target, and measured energy balance agree."
    : "Where your goal, program, calorie target, and eating don't line up.";
  return (
    <div
      className={`flex items-center gap-3 px-4 py-3 ${flags.length > 0 ? "border-b-[0.5px] border-border-subtle" : ""}`}
    >
      <span
        className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${synced ? "bg-good-bg" : "bg-warn-bg"}`}
      >
        <i
          className={`ti ti-${synced ? "circle-check" : "alert-triangle"} text-[16px] ${synced ? "text-good" : "text-warn"}`}
          aria-hidden
        />
      </span>
      <div className="min-w-0">
        <p className="text-[13px] font-medium text-primary">{title}</p>
        <p className="text-[12px] leading-snug text-tertiary">{sub}</p>
      </div>
    </div>
  );
}

function FlagRow({
  flag,
  onReconcile,
}: {
  flag: PlanFlag;
  onReconcile?: (kind: PlanActionKind) => Promise<void>;
}) {
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  function run(kind: PlanActionKind) {
    setError(null);
    if (!onReconcile) {
      setDone(true); // preview: no-op
      return;
    }
    startTransition(async () => {
      try {
        await onReconcile(kind);
        setDone(true);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed");
      }
    });
  }

  const tone = FLAG_TONE[flag.severity];
  return (
    <div className="flex items-start gap-3 px-4 py-3">
      <span
        className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${tone.chip}`}
      >
        <i className={`ti ti-${flag.icon} text-[15px] ${tone.icon}`} aria-hidden />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium text-primary">{flag.title}</p>
        <p className="mt-0.5 text-[12px] leading-snug text-secondary">
          {flag.detail}
        </p>
        {flag.hint && (
          <p className="mt-0.5 text-[11px] leading-snug text-tertiary">
            {flag.hint}
          </p>
        )}
        {error && (
          <p className="mt-1 font-mono text-[11px] text-alert">{error}</p>
        )}
      </div>
      {flag.action ? (
        done ? (
          <span className="mt-1.5 shrink-0 caps-mono text-[10px] tracking-[0.06em] text-accent-dim">
            ✓ Done
          </span>
        ) : (
          <button
            type="button"
            disabled={pending}
            onClick={() => run(flag.action!.kind)}
            className={`mt-1 shrink-0 cursor-pointer rounded-md px-2.5 py-1 text-[11px] font-medium disabled:opacity-60 ${tone.button}`}
          >
            {pending ? "…" : flag.action.label}
          </button>
        )
      ) : null}
    </div>
  );
}

const FLAG_TONE: Record<
  PlanFlag["severity"],
  { chip: string; icon: string; button: string }
> = {
  action: {
    chip: "bg-accent-bg",
    icon: "text-accent-dim",
    button: "bg-accent text-inverse hover:opacity-90",
  },
  warn: {
    chip: "bg-warn-bg",
    icon: "text-warn",
    button:
      "border-[0.5px] border-border-strong bg-canvas text-primary hover:border-accent",
  },
  info: {
    chip: "bg-canvas-muted",
    icon: "text-neutral",
    button:
      "border-[0.5px] border-border-strong bg-canvas text-primary hover:border-accent",
  },
};

// ── Chain row ───────────────────────────────────────────────────────

function ChainRow({
  label,
  value,
  sub,
  warn = false,
  muted = false,
}: {
  label: string;
  value: string;
  sub?: string;
  warn?: boolean;
  muted?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 rounded-[12px] border-[0.5px] border-border-default bg-surface px-4 py-3">
      <span className="w-[128px] shrink-0 caps-mono text-[10px] tracking-[0.06em] text-tertiary">
        {label}
      </span>
      <div className="min-w-0 flex-1">
        <p
          className={`truncate text-[14px] ${
            muted ? "text-tertiary" : warn ? "text-warn" : "text-primary"
          }`}
        >
          {value}
        </p>
        {sub && <p className="truncate text-[12px] text-tertiary">{sub}</p>}
      </div>
    </div>
  );
}

// ── bits ────────────────────────────────────────────────────────────

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="caps-mono text-[11px] tracking-[0.06em] text-tertiary">
      {children}
    </h2>
  );
}

function macroLine(
  m: { proteinGrams: number | null; carbsGrams: number | null; fatGrams: number | null } | null,
): string | undefined {
  if (!m) return undefined;
  const parts: string[] = [];
  if (m.proteinGrams != null) parts.push(`${Math.round(m.proteinGrams)}P`);
  if (m.carbsGrams != null) parts.push(`${Math.round(m.carbsGrams)}C`);
  if (m.fatGrams != null) parts.push(`${Math.round(m.fatGrams)}F`);
  return parts.length ? parts.join(" · ") : undefined;
}

function describeBalance(balance: number): string {
  const r = Math.round(balance);
  if (Math.abs(r) < 75) return "roughly at maintenance";
  return r > 0 ? `+${fmt(r)} surplus` : `${fmt(r)} deficit`;
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

function titleCase(s: string): string {
  return s
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(" ");
}
