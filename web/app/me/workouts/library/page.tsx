import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import {
  listAdHocWorkouts,
  archiveAdHocWorkout,
  restoreAdHocWorkout,
  updateAdHocWorkout,
} from "@/lib/adhoc-api";
import type { AdHocSort, AdHocWorkoutSummary } from "@/lib/types/adhoc";
import { formatDuration } from "@/lib/workout-format";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workout Library");
export const dynamic = "force-dynamic";

const SORTS: { id: AdHocSort; label: string }[] = [
  { id: "recent", label: "Recent" },
  { id: "mostDone", label: "Most done" },
  { id: "created", label: "Newest" },
];

type SearchParams = { sort?: string; tag?: string; archived?: string };

export default async function WorkoutLibraryPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const sort = (SORTS.find((s) => s.id === params.sort)?.id ?? "recent") as AdHocSort;
  const includeArchived = params.archived === "true";
  const tag = params.tag;

  let items: AdHocWorkoutSummary[] = [];
  try {
    items = await listAdHocWorkouts({ sort, tag, includeArchived });
  } catch {
    items = [];
  }

  // Distinct tags across the (unfiltered) library for the filter chips.
  const allTags = Array.from(
    new Set(items.flatMap((w) => w.tags ?? [])),
  ).sort();

  async function archive(formData: FormData): Promise<void> {
    "use server";
    await archiveAdHocWorkout(String(formData.get("adhocId")));
    revalidatePath("/me/workouts/library");
  }

  async function restore(formData: FormData): Promise<void> {
    "use server";
    await restoreAdHocWorkout(String(formData.get("adhocId")));
    revalidatePath("/me/workouts/library");
  }

  async function togglePin(formData: FormData): Promise<void> {
    "use server";
    await updateAdHocWorkout(String(formData.get("adhocId")), {
      pinned: formData.get("pinned") === "true",
    });
    revalidatePath("/me/workouts/library");
  }

  const q = (extra: Partial<SearchParams>) => {
    const p = new URLSearchParams();
    if ((extra.sort ?? sort) !== "recent") p.set("sort", extra.sort ?? sort);
    if (extra.tag ?? tag) p.set("tag", extra.tag ?? (tag as string));
    if ((extra.archived ?? String(includeArchived)) === "true") {
      p.set("archived", "true");
    }
    const s = p.toString();
    return (`/me/workouts/library${s ? `?${s}` : ""}`) as Route;
  };

  return (
    <main className="bg-canvas px-8 pb-16 pt-6">
      <div className="mx-auto max-w-[920px]">
        {/* The section chrome (back-to-dashboard, "Workouts" title, tab bar) is
            provided by the shared workouts layout; this page owns its own header. */}
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Library
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Purpose-driven workouts you can knock out whenever — travel, home, or
              a quick session. Generated to fit your time and equipment.
            </p>
          </div>
          <Link
            href={"/me/workouts/library/generate" as Route}
            className="caps-mono inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
          >
            Generate
          </Link>
        </header>

        {/* Sort + archived toggle */}
        <div className="mt-5 flex flex-wrap items-center gap-2">
        {SORTS.map((s) => (
          <Link
            key={s.id}
            href={q({ sort: s.id })}
            className={`rounded-full border-[0.5px] px-3 py-1 text-[12px] ${
              s.id === sort
                ? "border-accent bg-accent/10 text-accent"
                : "border-border-default text-secondary hover:text-primary"
            }`}
          >
            {s.label}
          </Link>
        ))}
        <span className="mx-1 text-border-default">·</span>
        <Link
          href={q({ archived: includeArchived ? "false" : "true" })}
          className={`rounded-full border-[0.5px] px-3 py-1 text-[12px] ${
            includeArchived
              ? "border-accent bg-accent/10 text-accent"
              : "border-border-default text-secondary hover:text-primary"
          }`}
        >
          {includeArchived ? "Hiding archived" : "Show archived"}
        </Link>
      </div>

      {/* Tag filter chips */}
      {allTags.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Link
            href={q({ tag: undefined })}
            className={`rounded-full px-2.5 py-0.5 text-[11px] ${
              !tag ? "bg-accent/10 text-accent" : "bg-canvas text-tertiary hover:text-secondary"
            }`}
          >
            All
          </Link>
          {allTags.map((t) => (
            <Link
              key={t}
              href={q({ tag: t })}
              className={`rounded-full px-2.5 py-0.5 text-[11px] ${
                tag === t ? "bg-accent/10 text-accent" : "bg-canvas text-tertiary hover:text-secondary"
              }`}
            >
              {t}
            </Link>
          ))}
        </div>
      )}

      {/* List */}
      <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
        {items.length === 0 && (
          <div className="col-span-full rounded-[14px] border-[0.5px] border-dashed border-border-default px-6 py-10 text-center">
            <p className="text-[14px] text-secondary">No saved workouts yet.</p>
            <Link
              href={"/me/workouts/library/generate" as Route}
              className="mt-3 inline-block rounded-full bg-accent px-4 py-2 text-[13px] font-medium text-white hover:opacity-90"
            >
              Generate your first
            </Link>
          </div>
        )}
        {items.map((w) => (
          <AdHocCard
            key={w.adhocId}
            w={w}
            includeArchived={includeArchived}
            archive={archive}
            restore={restore}
            togglePin={togglePin}
          />
        ))}
        </div>
      </div>
    </main>
  );
}

function AdHocCard({
  w,
  includeArchived,
  archive,
  restore,
  togglePin,
}: {
  w: AdHocWorkoutSummary;
  includeArchived: boolean;
  archive: (fd: FormData) => Promise<void>;
  restore: (fd: FormData) => Promise<void>;
  togglePin: (fd: FormData) => Promise<void>;
}) {
  const est = w.estimatedDurationSeconds
    ? formatDuration(w.estimatedDurationSeconds)
    : null;
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-5 py-4">
      <div className="flex items-start justify-between gap-2">
        <Link href={`/me/workouts/library/${w.adhocId}` as Route} className="group min-w-0">
          <h2 className="truncate text-[15px] font-medium text-primary group-hover:text-accent">
            {w.pinned ? "★ " : ""}
            {w.title}
          </h2>
          {w.summary && (
            <p className="mt-0.5 line-clamp-2 text-[12px] text-secondary">{w.summary}</p>
          )}
        </Link>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-tertiary">
        {est && <span>~{est}</span>}
        {w.runCount > 0 && <span>· done {w.runCount}×</span>}
        {w.equipmentContext?.label && <span>· {w.equipmentContext.label}</span>}
      </div>
      {w.tags.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {w.tags.map((t) => (
            <span key={t} className="rounded-full bg-canvas px-2 py-0.5 text-[10px] text-tertiary">
              {t}
            </span>
          ))}
        </div>
      )}
      <div className="mt-3 flex items-center gap-3 border-t border-border-subtle pt-2 text-[12px]">
        <form action={togglePin}>
          <input type="hidden" name="adhocId" value={w.adhocId} />
          <input type="hidden" name="pinned" value={String(!w.pinned)} />
          <button type="submit" className="text-secondary hover:text-primary">
            {w.pinned ? "Unpin" : "Pin"}
          </button>
        </form>
        {includeArchived ? (
          <form action={restore}>
            <input type="hidden" name="adhocId" value={w.adhocId} />
            <button type="submit" className="text-accent hover:opacity-80">
              Restore
            </button>
          </form>
        ) : (
          <form action={archive}>
            <input type="hidden" name="adhocId" value={w.adhocId} />
            <button type="submit" className="text-secondary hover:text-red-500">
              Archive
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
