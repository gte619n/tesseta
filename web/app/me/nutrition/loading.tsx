// Loading boundary for /me/nutrition: header + daily summary + meal-section
// skeletons matching the page's footprint, so navigating here (or between days)
// swaps in instantly instead of freezing through the day/target/recents fetches.
// (router.refresh() re-renders in place and never shows this — only navigations do.)
export default function Loading() {
  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <div>
          <div className="h-4 w-28 animate-pulse rounded bg-surface" />
          <div className="mt-3 h-7 w-44 animate-pulse rounded-md bg-surface" />
        </div>
        {/* Daily summary card */}
        <div className="h-[200px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
        {/* Meal sections */}
        {Array.from({ length: 4 }, (_, i) => (
          <div
            key={i}
            className="h-[120px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface"
          />
        ))}
      </div>
    </main>
  );
}
