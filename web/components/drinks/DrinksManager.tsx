"use client";

import { useState } from "react";
import type {
  Drink,
  DrinkProposal,
  SaveDrinkBody,
} from "@/lib/types/nutrition";
import { useToast } from "@/components/ui/Toast";
import { PendingImageRefresher } from "@/components/nutrition/PendingImageRefresher";
import { DrinkForm } from "@/components/drinks/DrinkForm";
import { DrinkRow } from "@/components/drinks/DrinkRow";

// The add-a-drink flow is a small state machine (D9 — one at a time):
//   idle  → the name input + "Analyze with AI" button
//   review → the review/edit form, pre-filled from the AI proposal
//   manual → the review/edit form, blank (AI failed / user chose manual entry)
type AddState =
  | { mode: "idle" }
  | { mode: "review"; proposal: DrinkProposal }
  | { mode: "manual"; name: string };

/**
 * Web drink catalog manager (IMPL-DRINK-01, web = setup only). Owns the
 * add-one-at-a-time flow (name → AI analyze → review/edit → save), the list of
 * my drinks with full CRUD, and keeps the page polling while any drink image is
 * still generating so it refreshes to READY without a manual reload.
 */
export function DrinksManager({
  drinks,
  analyze,
  create,
  update,
  regenerate,
  archive,
}: {
  drinks: Drink[];
  // Returns null when the analyzer is unavailable (backend 422) — fall back to
  // manual entry with whatever name the user typed.
  analyze: (name: string) => Promise<DrinkProposal | null>;
  create: (body: SaveDrinkBody) => Promise<void>;
  update: (foodId: string, body: SaveDrinkBody) => Promise<void>;
  regenerate: (foodId: string) => Promise<void>;
  archive: (foodId: string) => Promise<void>;
}) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [analyzing, setAnalyzing] = useState(false);
  const [add, setAdd] = useState<AddState>({ mode: "idle" });

  // Poll while any drink's image is still generating (PENDING) so it appears as
  // soon as it's READY — the same mechanism the nutrition day view uses.
  const hasPending = drinks.some((d) => d.imageStatus === "PENDING");

  async function handleAnalyze() {
    const trimmed = name.trim();
    if (trimmed === "") return;
    setAnalyzing(true);
    try {
      const proposal = await analyze(trimmed);
      if (proposal) {
        setAdd({ mode: "review", proposal });
      } else {
        // AI unavailable / couldn't parse — let the user fill it in manually.
        toast.info("Couldn't analyze that — enter the details manually.");
        setAdd({ mode: "manual", name: trimmed });
      }
    } catch {
      toast.error("Something went wrong analyzing the drink.");
    } finally {
      setAnalyzing(false);
    }
  }

  function resetAdd() {
    setAdd({ mode: "idle" });
    setName("");
  }

  async function handleCreate(body: SaveDrinkBody) {
    await create(body);
    resetAdd();
    toast.success("Drink saved — generating a picture…");
  }

  return (
    <div className="space-y-6">
      <PendingImageRefresher active={hasPending} />

      {/* Add a drink */}
      <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
        <h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">
          Add a drink
        </h2>

        {add.mode === "idle" && (
          <div className="mt-3">
            <div className="flex flex-col gap-2 sm:flex-row">
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !analyzing) handleAnalyze();
                }}
                placeholder="e.g. Negroni, Vodka Soda, Guinness"
                className="flex-1 rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary outline-none focus:border-accent"
              />
              <button
                type="button"
                onClick={handleAnalyze}
                disabled={analyzing || name.trim() === ""}
                className="inline-flex items-center justify-center gap-1.5 rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse disabled:opacity-50"
              >
                {analyzing ? (
                  <>
                    <i className="ti ti-loader-2 animate-spin text-[14px]" aria-hidden />
                    Analyzing…
                  </>
                ) : (
                  <>
                    <i className="ti ti-sparkles text-[14px]" aria-hidden />
                    Analyze with AI
                  </>
                )}
              </button>
            </div>
            <button
              type="button"
              onClick={() => setAdd({ mode: "manual", name: name.trim() })}
              className="mt-2 cursor-pointer font-mono text-[11px] text-tertiary underline-offset-2 hover:text-secondary hover:underline"
            >
              or enter the details manually
            </button>
          </div>
        )}

        {add.mode === "review" && (
          <div className="mt-4">
            <p className="mb-3 text-[12px] leading-[1.5] text-secondary">
              Review the AI estimate and adjust anything before saving. The drink
              is only saved (and its picture generated) once you confirm.
            </p>
            <DrinkForm
              proposal={add.proposal}
              submitLabel="Save drink"
              onSave={handleCreate}
              onCancel={resetAdd}
            />
          </div>
        )}

        {add.mode === "manual" && (
          <div className="mt-4">
            <p className="mb-3 text-[12px] leading-[1.5] text-secondary">
              Enter the drink details. ABV and serving volume are required.
            </p>
            <DrinkForm
              proposal={{
                name: add.name,
                abvPercent: null,
                servingVolumeMl: null,
                servingMacros: {
                  caloriesKcal: null,
                  proteinGrams: null,
                  carbsGrams: null,
                  fatGrams: null,
                  fiberGrams: null,
                  sugarGrams: null,
                },
                alcohol: null,
              }}
              submitLabel="Save drink"
              onSave={handleCreate}
              onCancel={resetAdd}
            />
          </div>
        )}
      </section>

      {/* My drinks */}
      <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
        <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
          <h2 className="m-0 text-[14px] font-medium text-primary">
            My drinks{drinks.length > 0 && ` (${drinks.length})`}
          </h2>
        </div>
        {drinks.length > 0 ? (
          <div>
            {drinks.map((drink) => (
              <DrinkRow
                key={drink.foodId}
                drink={drink}
                update={update}
                regenerate={regenerate}
                archive={archive}
              />
            ))}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center px-5 py-12 text-center">
            <div className="mb-3 rounded-full bg-canvas-sunken p-4">
              <i className="ti ti-glass-cocktail text-[22px] text-tertiary" aria-hidden />
            </div>
            <h3 className="text-[14px] font-medium text-primary">
              No drinks yet
            </h3>
            <p className="mt-1 text-[13px] text-secondary">
              Add your first drink above to start building your catalog.
            </p>
          </div>
        )}
      </section>
    </div>
  );
}
