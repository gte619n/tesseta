"use client";

// Presentational sparkline polyline. Extracted into its own client-safe leaf
// module so it can be imported by BOTH the server-rendered `StatCard` and the
// `"use client"` `WeightStatCard`. Importing it out of `StatCard.tsx` (a module
// that also exports Server Components) corrupted the React Client Manifest in
// the standalone build — the deploy crashed with "Could not find the module
// WeightStatCard.tsx#WeightStatCard in the React Client Manifest" whenever the
// weight-data path rendered. A dedicated leaf module keeps the boundary clean.
export function Sparkline({
  points,
  width = 48,
  height = 18,
}: {
  points: string;
  width?: number;
  height?: number;
}) {
  return (
    <svg width={width} height={height} viewBox="0 0 48 20" aria-hidden>
      <polyline
        points={points}
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth="1.5"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}
