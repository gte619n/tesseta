import Link from "next/link";
import { revalidatePath } from "next/cache";
import { createLocation } from "@/lib/gym-api";
import { LocationForm } from "@/components/gym/LocationForm";
import type { CreateLocationRequest } from "@/lib/types/gym";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("New Gym");

export default function NewGymPage() {
  async function createLocationAction(formData: FormData) {
    "use server";

    const name = formData.get("name") as string;
    const address = formData.get("address") as string;
    const is24Hours = formData.get("is24Hours") === "true";
    const hoursRaw = formData.get("hours") as string;
    const amenitiesRaw = formData.get("amenities") as string;

    const hours = hoursRaw ? JSON.parse(hoursRaw) : null;
    const amenities = amenitiesRaw ? JSON.parse(amenitiesRaw) : [];

    const data: CreateLocationRequest = {
      name,
      address: address || null,
      is24Hours,
      hours,
      amenities,
      equipmentIds: [],
    };

    const location = await createLocation(data);
    revalidatePath("/me/workouts/gyms");
    return location;
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[640px] space-y-6">
        <Link
          href="/me/workouts/gyms"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Back to Gyms
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Add a gym
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Create a new gym location to track equipment and workouts.
          </p>
        </header>

        <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface p-6">
          <LocationForm
            onSubmit={createLocationAction}
            cancelHref="/me/workouts/gyms"
          />
        </div>
      </div>
    </main>
  );
}
