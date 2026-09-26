// Loading boundary for the Workouts section. Renders inside WorkoutsLayout, so
// the sticky header + tab bar stay in place while a tab navigation streams in —
// only the body swaps to this card-grid skeleton (mirroring the Overview's
// masonry footprint). Without it, clicking a workouts tab froze the whole UI
// for the page's full multi-fetch server render.
export default function Loading() {
  return (
    <main className="bg-canvas px-8 pb-16 pt-6">
      <div className="mx-auto max-w-[1040px]">
        <div className="columns-1 gap-4 lg:columns-2">
          {[220, 260, 200, 240, 260, 200].map((h, i) => (
            <div key={i} className="mb-4 break-inside-avoid">
              <div
                style={{ height: h }}
                className="animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface"
              />
            </div>
          ))}
        </div>
      </div>
    </main>
  );
}
