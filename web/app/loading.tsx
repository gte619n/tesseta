// Route-level loading boundary for the dashboard (and any route without a
// closer one). Every page here is force-dynamic, so without a loading.tsx the
// router shows NOTHING while the destination's server render completes — a
// click feels dead for the full backend round-trip. This skeleton mirrors the
// dashboard shell footprint so the swap-in is instant and layout-stable; it
// also gives <Link> prefetching a static boundary to prefetch up to.
export default function Loading() {
  return (
    <div className="flex min-h-screen items-start justify-center p-8">
      <div className="grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-canvas shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        {/* Sidebar column */}
        <div className="min-h-screen animate-pulse border-r-[0.5px] border-border-default bg-surface" />
        <main className="overflow-hidden px-7 pb-7 pt-[22px]">
          <div className="mb-4 h-8 w-48 animate-pulse rounded-md bg-surface" />
          <section className="mb-3 grid grid-cols-5 gap-2.5">
            {Array.from({ length: 5 }, (_, i) => (
              <div
                key={i}
                className="h-[92px] animate-pulse rounded-[9px] border-[0.5px] border-border-default bg-surface"
              />
            ))}
          </section>
          <div className="mb-3 h-[260px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
          <section className="mb-3 grid grid-cols-2 gap-2.5">
            <div className="h-[180px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
            <div className="h-[180px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
          </section>
        </main>
      </div>
    </div>
  );
}
