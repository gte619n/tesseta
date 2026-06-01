import Link from "next/link";
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import {
  getLocation,
  updateLocation,
  uploadCoverPhoto,
  deleteCoverPhoto,
} from "@/lib/gym-api";
import { LocationForm } from "@/components/gym/LocationForm";
import { LocationCoverPhotoUpload } from "@/components/gym/LocationCoverPhotoUpload";
import type { UpdateLocationRequest } from "@/lib/types/gym";
import { entityMetadata } from "@/lib/page-metadata";

export const generateMetadata = entityMetadata(
  ({ locationId }: { locationId: string }) => getLocation(locationId),
  (location) => `Edit ${location.name}`,
);

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

  async function uploadCoverPhotoAction(formData: FormData): Promise<string> {
    "use server";
    const file = formData.get("file");
    if (!(file instanceof File)) {
      throw new Error("file is required");
    }
    const url = await uploadCoverPhoto(locationId, file);
    revalidatePath("/me/workouts/gyms");
    revalidatePath(`/me/workouts/gyms/${locationId}`);
    return url;
  }

  async function deleteCoverPhotoAction(): Promise<void> {
    "use server";
    await deleteCoverPhoto(locationId);
    revalidatePath("/me/workouts/gyms");
    revalidatePath(`/me/workouts/gyms/${locationId}`);
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
          <section className="mb-6">
            <h2 className="mb-3 font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
              Cover photo
            </h2>
            <LocationCoverPhotoUpload
              currentPhotoUrl={location.coverPhotoUrl}
              uploadPhoto={uploadCoverPhotoAction}
              deletePhoto={deleteCoverPhotoAction}
            />
          </section>

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
