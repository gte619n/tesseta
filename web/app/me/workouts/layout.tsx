import Link from "next/link";
import { WorkoutTabs } from "@/components/workouts/WorkoutTabs";

// Shared chrome for the whole Workouts section (IMPL-WEB-WORKOUT-01 D1): a
// back-to-dashboard link, the section title, and the persistent tab bar that
// stays visible across Overview and every sub-page. Individual pages render
// their own content below; the Overview owns the dashboard, the sub-pages keep
// their existing bodies (de-duplicated in Phase 4).

export default function WorkoutsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-canvas">
      <div className="sticky top-0 z-20 border-b border-border-subtle bg-canvas/95 backdrop-blur">
        <div className="mx-auto max-w-[1040px] px-8 pt-6">
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
          >
            ← Dashboard
          </Link>
          <h1 className="mt-2 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Workouts
          </h1>
          <div className="mt-3 pb-2">
            <WorkoutTabs />
          </div>
        </div>
      </div>
      {children}
    </div>
  );
}
