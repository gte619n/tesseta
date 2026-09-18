"use client";

import { useState } from "react";
import { useToast } from "@/components/ui/Toast";

/**
 * Admin "clean up duplicate foods" control. Fires the one-off catalog dedupe
 * sweep and reports how many foods were archived. The sweep only sets
 * `archivedAt` (reversible) and search hides archived foods on every device.
 */
export function FoodDedupeClient({ dedupe }: { dedupe: () => Promise<number> }) {
  const toast = useToast();
  const [running, setRunning] = useState(false);
  const [lastCount, setLastCount] = useState<number | null>(null);

  async function handleDedupe() {
    setRunning(true);
    try {
      const archived = await dedupe();
      setLastCount(archived);
      toast.success(
        archived === 0
          ? "No duplicates found — the catalog is clean"
          : `Archived ${archived} duplicate food${archived === 1 ? "" : "s"}`,
      );
    } catch {
      toast.error("Dedupe failed — try again");
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="rounded-[12px] border-[0.5px] border-border-default bg-surface p-5">
      <h2 className="m-0 text-base font-medium text-primary">Remove duplicate foods</h2>
      <p className="mt-1 text-[13px] leading-relaxed text-secondary">
        Scans the shared food catalog and archives near-identical entries (same
        name, brand and per-100&nbsp;g macros), keeping the best of each group.
        Archived foods drop out of search everywhere; nothing is deleted, so it&rsquo;s
        reversible. Safe to re-run.
      </p>
      <button
        type="button"
        onClick={handleDedupe}
        disabled={running}
        className="mt-4 inline-flex cursor-pointer items-center gap-2 rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse disabled:opacity-50"
      >
        <i
          className={`ti ti-${running ? "loader-2 animate-spin" : "wand"} text-[14px]`}
          aria-hidden
        />
        {running ? "Cleaning up…" : "Clean up duplicates"}
      </button>
      {lastCount !== null && !running && (
        <p className="mt-3 text-[12px] text-tertiary">
          Last run archived {lastCount} food{lastCount === 1 ? "" : "s"}.
        </p>
      )}
    </div>
  );
}
