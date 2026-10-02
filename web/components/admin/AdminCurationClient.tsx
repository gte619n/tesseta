"use client";

import { useState, useTransition } from "react";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import {
  CURATABLE_TYPES,
  type CurationQueueItem,
  type CurationType,
} from "@/lib/types/curation";

interface Props {
  items: CurationQueueItem[];
  approve: (type: CurationType, id: string) => Promise<void>;
  reject: (type: CurationType, id: string, reason: string) => Promise<void>;
}

// Where the non-curatable types' own approve/reject consoles live. Used to
// link out rather than duplicate their mutation logic (P3.5 contract).
const DEEP_LINK: Partial<Record<CurationType, Route>> = {
  equipment: "/admin/equipment/review" as Route,
  exercise: "/admin/exercises/review" as Route,
};

const TYPE_LABEL: Record<CurationType, string> = {
  program: "Programs",
  adhoc: "Ad-hoc workouts",
  equipment: "Equipment",
  exercise: "Exercises",
  food: "Foods",
};

const TYPE_ORDER: CurationType[] = [
  "program",
  "adhoc",
  "equipment",
  "exercise",
  "food",
];

function fmt(iso: string | null): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

export function AdminCurationClient({ items, approve, reject }: Props) {
  const toast = useToast();
  const [busy, startBusy] = useTransition();
  const [actingId, setActingId] = useState<string | null>(null);

  const grouped = TYPE_ORDER.map((type) => ({
    type,
    rows: items.filter((i) => i.type === type),
  })).filter((g) => g.rows.length > 0);

  function onApprove(item: CurationQueueItem) {
    setActingId(item.id);
    startBusy(async () => {
      try {
        await approve(item.type, item.id);
        toast.success("Approved", { description: item.title });
      } catch (err) {
        toast.error("Approve failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      } finally {
        setActingId(null);
      }
    });
  }

  function onReject(item: CurationQueueItem) {
    const reason = window.prompt(`Reject "${item.title}" — reason?`, "");
    if (reason === null) return; // cancelled
    setActingId(item.id);
    startBusy(async () => {
      try {
        await reject(item.type, item.id, reason);
        toast.success("Rejected", { description: item.title });
      } catch (err) {
        toast.error("Reject failed", {
          description: err instanceof Error ? err.message : String(err),
        });
      } finally {
        setActingId(null);
      }
    });
  }

  if (grouped.length === 0) {
    return (
      <p className="py-12 text-center text-sm text-tertiary">
        Nothing pending review.
      </p>
    );
  }

  return (
    <div className="space-y-8">
      {grouped.map(({ type, rows }) => {
        const curatable = CURATABLE_TYPES.includes(type);
        const deepLink = DEEP_LINK[type];
        return (
          <section
            key={type}
            className="rounded-lg border border-border-default bg-surface p-5"
          >
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-base font-semibold text-primary">
                {TYPE_LABEL[type]}
              </h2>
              <span className="text-xs text-tertiary">{rows.length} pending</span>
            </div>
            <ul className="divide-y divide-border-default">
              {rows.map((item) => (
                <li
                  key={`${item.type}:${item.id}`}
                  className="flex items-center justify-between gap-4 py-3"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-primary">
                      {item.title}
                    </p>
                    <p className="truncate text-xs text-tertiary">
                      {item.contributorId
                        ? `contributor ${item.contributorId} · `
                        : ""}
                      submitted {fmt(item.submittedAt)}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {curatable ? (
                      <>
                        <button
                          type="button"
                          disabled={busy && actingId === item.id}
                          onClick={() => onApprove(item)}
                          className="rounded-full bg-accent px-3.5 py-1.5 text-sm font-medium text-inverse hover:bg-accent-dim disabled:opacity-50"
                        >
                          Approve
                        </button>
                        <button
                          type="button"
                          disabled={busy && actingId === item.id}
                          onClick={() => onReject(item)}
                          className="rounded-full border border-danger px-3.5 py-1.5 text-sm font-medium text-danger hover:bg-danger-muted disabled:opacity-50"
                        >
                          Reject
                        </button>
                      </>
                    ) : deepLink ? (
                      <Link
                        href={deepLink}
                        className="rounded-full border border-border-default px-3.5 py-1.5 text-sm font-medium text-primary hover:bg-canvas-muted"
                      >
                        Review in console
                      </Link>
                    ) : (
                      <span className="text-xs text-tertiary">
                        Review in its console
                      </span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
