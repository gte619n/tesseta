"use client";

import { useState, useTransition } from "react";
import type { Drink, SaveDrinkBody } from "@/lib/types/nutrition";
import { formatNumber, formatWholeNumber } from "@/lib/format-number";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { DrinkImage } from "@/components/drinks/DrinkImage";
import { DrinkForm } from "@/components/drinks/DrinkForm";

/**
 * One drink in the catalog list, with inline edit (PUT), regenerate-image
 * (POST image/regenerate) and archive (DELETE, confirmed) — full web CRUD (D20).
 * A FAILED/PENDING image still renders a glassware placeholder and offers a
 * regenerate; the parent's poller flips it to READY when generation lands.
 */
export function DrinkRow({
  drink,
  update,
  regenerate,
  archive,
}: {
  drink: Drink;
  update: (foodId: string, body: SaveDrinkBody) => Promise<void>;
  regenerate: (foodId: string) => Promise<void>;
  archive: (foodId: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [regenerating, startRegen] = useTransition();
  const toast = useToast();
  const confirm = useConfirm();

  const a = drink.alcohol;
  const abv = a?.abvPercent ?? null;
  const volume = a?.servingVolumeMl ?? null;
  const std = a?.standardDrinks ?? null;
  const kcal =
    drink.servingSizes[drink.defaultServingIndex]?.grams != null
      ? computeServingKcal(drink)
      : null;

  const failed = drink.imageStatus === "FAILED";

  async function handleRegenerate() {
    try {
      await regenerate(drink.foodId);
      toast.info("Regenerating image…");
    } catch {
      toast.error("Couldn't regenerate the image");
    }
  }

  async function handleArchive() {
    const ok = await confirm({
      title: "Remove this drink?",
      description: `Remove "${drink.name}" from your drink catalog? Drinks you've already logged are unaffected.`,
      confirmLabel: "Remove",
      tone: "danger",
    });
    if (!ok) return;
    try {
      await archive(drink.foodId);
      toast.success("Drink removed");
    } catch {
      toast.error("Couldn't remove the drink");
    }
  }

  async function handleSaveEdit(body: SaveDrinkBody) {
    await update(drink.foodId, body);
    setEditing(false);
    toast.success("Drink updated");
  }

  if (editing) {
    return (
      <div className="border-b-[0.5px] border-border-subtle px-5 py-4 last:border-b-0">
        <DrinkForm
          drink={drink}
          submitLabel="Save changes"
          onSave={handleSaveEdit}
          onCancel={() => setEditing(false)}
        />
      </div>
    );
  }

  return (
    <div className="group flex items-center gap-3 border-b-[0.5px] border-border-subtle px-5 py-3 last:border-b-0 hover:bg-canvas-sunken/30">
      <DrinkImage imageUrl={drink.imageUrl} imageStatus={drink.imageStatus} size={48} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-medium text-primary">
          {drink.name}
        </div>
        <div className="mt-0.5 caps-mono text-[9px] tracking-[0.04em] text-tertiary">
          {abv != null ? `${formatNumber(abv, 1)}% ABV` : "—"}
          {volume != null && ` · ${formatWholeNumber(volume)}ml`}
          {failed && (
            <span className="ml-1.5 text-alert">· image failed</span>
          )}
        </div>
      </div>
      <div className="hidden shrink-0 items-center gap-4 sm:flex">
        <Stat
          label="Std"
          value={std != null ? formatNumber(std, 1) : "—"}
        />
        <Stat
          label="kcal"
          value={kcal != null ? formatWholeNumber(kcal) : "—"}
        />
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <button
          type="button"
          onClick={() => startRegen(() => handleRegenerate())}
          disabled={regenerating}
          className="cursor-pointer rounded p-1.5 text-tertiary hover:text-accent-dim disabled:opacity-50"
          aria-label={`Regenerate image for ${drink.name}`}
          title="Regenerate image"
        >
          <i
            className={`ti ti-refresh text-[14px] ${regenerating ? "animate-spin" : ""}`}
            aria-hidden
          />
        </button>
        <button
          type="button"
          onClick={() => setEditing(true)}
          className="cursor-pointer rounded p-1.5 text-tertiary hover:text-accent-dim"
          aria-label={`Edit ${drink.name}`}
          title="Edit"
        >
          <i className="ti ti-pencil text-[14px]" aria-hidden />
        </button>
        <button
          type="button"
          onClick={handleArchive}
          className="cursor-pointer rounded p-1.5 text-tertiary hover:text-alert"
          aria-label={`Remove ${drink.name}`}
          title="Remove"
        >
          <i className="ti ti-trash text-[14px]" aria-hidden />
        </button>
      </div>
    </div>
  );
}

// The backend echoes the default serving's calories (incl. folded-in alcohol
// calories) on `servingMacros`; use it directly for the list readout so the
// number doesn't drift on the per-100g round-trip (IMPL-DRINK-01 IL-13). Falls
// back to recomputing from per-100(ml) macros if `servingMacros` is absent.
function computeServingKcal(drink: Drink): number | null {
  const serving =
    drink.servingSizes[drink.defaultServingIndex] ?? drink.servingSizes[0];
  const echoed = drink.servingMacros?.caloriesKcal;
  if (echoed != null) return echoed;
  const grams = serving?.grams;
  const per100 = drink.macrosPer100g?.caloriesKcal;
  if (grams == null || per100 == null) return null;
  return (per100 * grams) / 100;
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-right">
      <div className="font-mono text-[13px] font-medium tabular-nums text-primary">
        {value}
      </div>
      <div className="caps-mono text-[8px] tracking-[0.06em] text-tertiary">
        {label}
      </div>
    </div>
  );
}
