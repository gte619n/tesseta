"use client";

import { useState } from "react";
import type { AdjustApplyBody, AdjustItem, AdjustPreviewResponse } from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";

type Props = {
  isComposite: boolean;
  // Bound to this entry's date + id by the parent modal.
  adjustPreview: (instruction: string) => Promise<AdjustPreviewResponse>;
  adjustApply: (body: AdjustApplyBody) => Promise<void>;
  // Called after a successful apply so the modal can close.
  onApplied: () => void;
};

function itemSummary(item: AdjustItem): string {
  const parts: string[] = [];
  if (item.servingGrams != null) parts.push(`${Math.round(item.servingGrams)} g`);
  if (item.macros?.caloriesKcal != null) parts.push(`${Math.round(item.macros.caloriesKcal)} kcal`);
  return parts.length ? `  ${parts.join(" · ")}` : "";
}

/**
 * "Adjust with AI" section for an entry's edit/ingredients modal — the free-text
 * correction flow. The user types a fix (e.g. "that's pearl couscous, not
 * lentils"); we run a server-side preview, show the before/after diff, and only
 * persist once they confirm. Shared by EditEntryModal and IngredientsModal.
 */
export function AdjustWithAi({ isComposite, adjustPreview, adjustApply, onApplied }: Props) {
  const toast = useToast();
  const [instruction, setInstruction] = useState("");
  const [loading, setLoading] = useState(false);
  const [applying, setApplying] = useState(false);
  const [proposal, setProposal] = useState<AdjustPreviewResponse | null>(null);
  const [saveAsMeal, setSaveAsMeal] = useState(false);

  async function handlePreview() {
    const text = instruction.trim();
    if (!text || loading) return;
    setLoading(true);
    try {
      setProposal(await adjustPreview(text));
    } catch {
      toast.error("Couldn't adjust the meal");
    } finally {
      setLoading(false);
    }
  }

  async function handleApply() {
    if (!proposal || applying) return;
    setApplying(true);
    try {
      await adjustApply({
        mealName: proposal.mealName,
        packagedProduct: proposal.packagedProduct,
        items: proposal.items,
        saveAsMeal: saveAsMeal && isComposite,
      });
      toast.success("Meal adjusted");
      onApplied();
    } catch {
      toast.error("Couldn't adjust the meal");
    } finally {
      setApplying(false);
    }
  }

  const oldKcal = proposal?.oldTotals.caloriesKcal;
  const newKcal = proposal?.newTotals.caloriesKcal;

  return (
    <div>
      <label className="mb-1.5 block text-[11px] font-medium text-secondary">Adjust with AI</label>
      <p className="mb-2 text-[12px] leading-snug text-secondary">
        Wrong food or portion? Describe the fix and let AI re-read the meal.
      </p>
      <input
        value={instruction}
        onChange={(e) => setInstruction(e.target.value)}
        placeholder="e.g. that's pearl couscous, not lentils"
        disabled={loading || applying}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            void handlePreview();
          }
        }}
        className="w-full rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-50"
      />

      {!proposal ? (
        <button
          type="button"
          onClick={handlePreview}
          disabled={loading || !instruction.trim()}
          className="mt-2 w-full cursor-pointer rounded-md border-[0.5px] border-accent bg-accent-bg px-3 py-2 text-[12px] font-medium text-accent-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          {loading ? "Thinking…" : "✨ Adjust with AI"}
        </button>
      ) : (
        <div className="mt-2.5 rounded-[10px] border-[0.5px] border-border-default bg-canvas p-3">
          <div className="caps-mono mb-1 text-[10px] tracking-[0.06em] text-tertiary">
            Proposed change
          </div>
          <div className="text-[14px] font-medium text-primary">{proposal.mealName}</div>
          <ul className="mt-1.5 space-y-0.5">
            {proposal.items.map((item, i) => (
              <li key={`${item.name}-${i}`} className="text-[12px] text-secondary">
                • {item.name}
                <span className="font-mono tabular-nums text-tertiary">{itemSummary(item)}</span>
              </li>
            ))}
          </ul>
          <div className="mt-2 font-mono text-[11px] tabular-nums text-secondary">
            Calories: {oldKcal != null ? Math.round(oldKcal) : "?"} →{" "}
            {newKcal != null ? Math.round(newKcal) : "?"} kcal
          </div>
          {isComposite && (
            <label className="mt-2 flex cursor-pointer items-center gap-2 text-[12px] text-secondary">
              <input
                type="checkbox"
                checked={saveAsMeal}
                disabled={applying}
                onChange={(e) => setSaveAsMeal(e.target.checked)}
                className="accent-accent"
              />
              Also save this meal so it&apos;s right next time
            </label>
          )}
          <div className="mt-3 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => {
                setProposal(null);
                setSaveAsMeal(false);
              }}
              disabled={applying}
              className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface px-3 py-1.5 text-[12px] font-medium text-primary disabled:opacity-50"
            >
              Discard
            </button>
            <button
              type="button"
              onClick={handleApply}
              disabled={applying}
              className="cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[12px] font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {applying ? "Applying…" : "Apply"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
