// Generic loading boundary for /me/* pages without a more specific one.
// These pages are force-dynamic and fetch several backend endpoints before
// rendering; this skeleton swaps in instantly on navigation instead of the
// router sitting frozen on the previous page.
export default function Loading() {
  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[1100px] space-y-6">
        <div>
          <div className="h-4 w-28 animate-pulse rounded bg-surface" />
          <div className="mt-3 h-7 w-56 animate-pulse rounded-md bg-surface" />
        </div>
        <div className="h-[200px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
        <div className="h-[160px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
        <div className="h-[160px] animate-pulse rounded-[10px] border-[0.5px] border-border-default bg-surface" />
      </div>
    </main>
  );
}
