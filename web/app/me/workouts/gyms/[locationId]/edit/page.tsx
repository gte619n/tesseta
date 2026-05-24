import Link from "next/link";
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import { getLocation, updateLocation } from "@/lib/gym-api";
import { LocationForm } from "@/components/gym/LocationForm";
import type { UpdateLocationRequest } from "@/lib/types/gym";

export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ locationId: string }>;
};

export default async function EditGymPage({ params }: Props) {
  const { locationId } = await params;

  let location;
  try {
    location = await getLocation(locationId);
  } catch {
    notFound();
  }

  async function updateLocationAction(formData: FormData) {
    "use server";

    const name = formData.get("name") as string;
    const address = formData.get("address") as string;
    const is24Hours = formData.get("is24Hours") === "true";
    const hoursRaw = formData.get("hours") as string;
    const amenitiesRaw = formData.get("amenities") as string;

    const hours = hoursRaw ? JSON.parse(hoursRaw) : null;
    const amenities = amenitiesRaw ? JSON.parse(amenitiesRaw) : [];

    const data: UpdateLocationRequest = {
      name,
      address: address || null,
      is24Hours,
      hours,
      amenities,
    };

    const updated = await updateLocation(locationId, data);
    revalidatePath("/me/workouts/gyms");
    revalidatePath(`/me/workouts/gyms/${locationId}`);
    return updated;
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[640px] space-y-6">
        <Link
          href={`/me/workouts/gyms/${locationId}`}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Back to {location.name}
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Edit gym
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Update location details, hours, and amenities.
          </p>
        </header>

        <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface p-6">
          <LocationForm
            initialData={location}
            onSubmit={updateLocationAction}
            cancelHref={`/me/workouts/gyms/${locationId}`}
          />
        </div>
      </div>
    </main>
  );
}
