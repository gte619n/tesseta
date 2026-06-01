import Link from "next/link";
import { getLocations } from "@/lib/gym-api";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Workouts");

export const dynamic = "force-dynamic";

export default async function WorkoutsPage() {
  const locations = await getLocations();
  const defaultLocation = locations.find((l) => l.isDefault);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Dashboard
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Workouts
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Track workouts, manage gyms, and view your training history.
          </p>
        </header>

        <section className="grid grid-cols-2 gap-4">
          {/* Gyms Card */}
          <Link
            href="/me/workouts/gyms"
            className="group rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 transition-colors hover:border-accent/60"
          >
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="text-accent"
                  >
                    <path d="M17.8 19.2L16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z" />
                  </svg>
                  <h2 className="text-[16px] font-medium text-primary">
                    Gyms
                  </h2>
                </div>
                <p className="mt-2 text-[13px] text-secondary">
                  Manage gym locations and track equipment.
                </p>
              </div>
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="text-tertiary transition-colors group-hover:text-accent"
              >
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </div>
            {locations.length > 0 && (
              <div className="mt-4 border-t border-border-subtle pt-3">
                <div className="flex items-center gap-2 text-[12px] text-tertiary">
                  <span className="font-medium text-primary">
                    {locations.length}
                  </span>
                  location{locations.length !== 1 && "s"}
                  {defaultLocation && (
                    <>
                      <span className="text-border-default">•</span>
                      <span>Default: {defaultLocation.name}</span>
                    </>
                  )}
                </div>
              </div>
            )}
          </Link>

          {/* Workout History Card - Coming Soon */}
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 opacity-50">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="text-tertiary"
                  >
                    <path d="M12 8v4l3 3" />
                    <circle cx="12" cy="12" r="10" />
                  </svg>
                  <h2 className="text-[16px] font-medium text-primary">
                    History
                  </h2>
                </div>
                <p className="mt-2 text-[13px] text-secondary">
                  View past workouts and track progress.
                </p>
              </div>
            </div>
            <div className="mt-4 border-t border-border-subtle pt-3">
              <span className="rounded-full bg-canvas px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-tertiary">
                Coming soon
              </span>
            </div>
          </div>

          {/* Log Workout Card - Coming Soon */}
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 opacity-50">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="text-tertiary"
                  >
                    <line x1="12" y1="5" x2="12" y2="19" />
                    <line x1="5" y1="12" x2="19" y2="12" />
                  </svg>
                  <h2 className="text-[16px] font-medium text-primary">
                    Log Workout
                  </h2>
                </div>
                <p className="mt-2 text-[13px] text-secondary">
                  Record exercises, sets, and reps.
                </p>
              </div>
            </div>
            <div className="mt-4 border-t border-border-subtle pt-3">
              <span className="rounded-full bg-canvas px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-tertiary">
                Coming soon
              </span>
            </div>
          </div>

          {/* Programs Card - Coming Soon */}
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5 opacity-50">
            <div className="flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2">
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="text-tertiary"
                  >
                    <line x1="8" y1="6" x2="21" y2="6" />
                    <line x1="8" y1="12" x2="21" y2="12" />
                    <line x1="8" y1="18" x2="21" y2="18" />
                    <line x1="3" y1="6" x2="3.01" y2="6" />
                    <line x1="3" y1="12" x2="3.01" y2="12" />
                    <line x1="3" y1="18" x2="3.01" y2="18" />
                  </svg>
                  <h2 className="text-[16px] font-medium text-primary">
                    Programs
                  </h2>
                </div>
                <p className="mt-2 text-[13px] text-secondary">
                  Create and follow training programs.
                </p>
              </div>
            </div>
            <div className="mt-4 border-t border-border-subtle pt-3">
              <span className="rounded-full bg-canvas px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-tertiary">
                Coming soon
              </span>
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}
