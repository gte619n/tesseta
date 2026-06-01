import Link from "next/link";
import { getLocations } from "@/lib/gym-api";
import { LocationCard } from "@/components/gym/LocationCard";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Gyms");

export const dynamic = "force-dynamic";

export default async function GymsPage() {
  const locations = await getLocations();

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/me/workouts"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Workouts
        </Link>

        <header className="flex items-start justify-between">
          <div>
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              Gyms
            </h1>
            <p className="mt-1 text-[13px] text-secondary">
              Manage your gym locations and equipment.
            </p>
          </div>
          <Link
            href="/me/workouts/gyms/new"
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse"
          >
            + Add Gym
          </Link>
        </header>

        {locations.length === 0 ? (
          <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-8 py-12 text-center">
            <p className="text-[14px] text-secondary">
              No gyms yet. Add your first gym to track equipment.
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-4">
            {locations.map((location) => (
              <LocationCard key={location.locationId} location={location} />
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
