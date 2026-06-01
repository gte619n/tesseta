import Link from "next/link";
import { notFound } from "next/navigation";
import { revalidatePath } from "next/cache";
import {
  getLocation,
  deleteLocation,
  setDefaultLocation,
  getEquipment,
  getEquipmentCatalog,
  submitEquipment,
  addEquipmentToLocation,
  removeEquipmentFromLocation,
  bulkImportPreview,
  bulkImportConfirm,
} from "@/lib/gym-api";
import { AMENITIES } from "@/lib/types/gym";
import type {
  DayOfWeek,
  Equipment,
  CreateEquipmentRequest,
  ImportPreviewResponse,
  ImportConfirmRequest,
  ImportConfirmResponse,
} from "@/lib/types/gym";
import { DeleteLocationButton } from "@/components/gym/DeleteLocationButton";
import { SetDefaultButton } from "@/components/gym/SetDefaultButton";
import { LocationEquipmentSection } from "@/components/gym/LocationEquipmentSection";
import { entityMetadata } from "@/lib/page-metadata";

export const generateMetadata = entityMetadata(
  ({ locationId }: { locationId: string }) => getLocation(locationId),
  (location) => location.name,
);

export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ locationId: string }>;
};

const DAY_LABELS: Record<DayOfWeek, string> = {
  mon: "Mon",
  tue: "Tue",
  wed: "Wed",
  thu: "Thu",
  fri: "Fri",
  sat: "Sat",
  sun: "Sun",
};

export default async function GymDetailPage({ params }: Props) {
  const { locationId } = await params;

  let location;
  try {
    location = await getLocation(locationId);
  } catch {
    notFound();
  }

  async function deleteLocationAction() {
    "use server";
    await deleteLocation(locationId);
    revalidatePath("/me/workouts/gyms");
  }

  async function setDefaultLocationAction() {
    "use server";
    await setDefaultLocation(locationId);
    revalidatePath("/me/workouts/gyms");
    revalidatePath(`/me/workouts/gyms/${locationId}`);
  }

  async function addEquipmentToLocationAction(equipmentId: string) {
    "use server";
    await addEquipmentToLocation(locationId, equipmentId);
    revalidatePath(`/me/workouts/gyms/${locationId}`);
  }

  async function removeEquipmentFromLocationAction(equipmentId: string) {
    "use server";
    await removeEquipmentFromLocation(locationId, equipmentId);
    revalidatePath(`/me/workouts/gyms/${locationId}`);
  }

  async function searchCatalogAction(
    search: string,
    category: string | null,
    subcategory: string | null,
  ): Promise<Equipment[]> {
    "use server";
    return getEquipmentCatalog({
      search: search || undefined,
      category: category || undefined,
      subcategory: subcategory || undefined,
    });
  }

  async function submitEquipmentAction(
    data: CreateEquipmentRequest,
  ): Promise<Equipment> {
    "use server";
    return submitEquipment(data);
  }

  async function bulkImportPreviewAction(
    rawText: string,
  ): Promise<ImportPreviewResponse> {
    "use server";
    return bulkImportPreview(locationId, rawText);
  }

  async function bulkImportConfirmAction(
    body: ImportConfirmRequest,
  ): Promise<ImportConfirmResponse> {
    "use server";
    return bulkImportConfirm(locationId, body);
  }

  // Pre-fetch equipment objects for the location's equipmentIds.
  // Tolerate per-item failures (a stale/deleted equipment ID shouldn't 500 the page).
  const equipmentResults = await Promise.all(
    location.equipmentIds.map((id) =>
      getEquipment(id).catch(() => null),
    ),
  );
  const equipment: Equipment[] = equipmentResults.filter(
    (e): e is Equipment => e !== null,
  );

  // Group hours by ranges with same times
  const hoursDisplay = formatHours(location);

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href="/me/workouts/gyms"
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Back to Gyms
        </Link>

        <header className="flex items-start justify-between">
          <div className="flex items-start gap-2">
            <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
              {location.name}
            </h1>
            {location.isDefault && (
              <span className="mt-1 text-[20px]" title="Default gym">
                ★
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Link
              href={`/me/workouts/gyms/${locationId}/edit`}
              className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary hover:bg-canvas-muted"
            >
              Edit
            </Link>
            <SetDefaultButton
              locationId={locationId}
              isDefault={location.isDefault}
              onSetDefault={setDefaultLocationAction}
            />
            <DeleteLocationButton
              locationId={locationId}
              locationName={location.name}
              onDelete={deleteLocationAction}
            />
          </div>
        </header>

        {location.coverPhotoUrl && (
          <div className="aspect-[21/9] overflow-hidden rounded-[14px]">
            <img
              src={location.coverPhotoUrl}
              alt={location.name}
              className="h-full w-full object-cover"
            />
          </div>
        )}

        <div className="grid gap-6 md:grid-cols-2">
          <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface p-5">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Details
            </h2>
            <div className="mt-4 space-y-3">
              <div>
                <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                  Address
                </div>
                <div className="mt-1 text-[13px] text-primary">
                  {location.address || "No address"}
                </div>
              </div>

              <div>
                <div className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
                  Hours
                </div>
                <div className="mt-1 space-y-1 text-[13px] text-primary">
                  {location.is24Hours ? (
                    <div>Open 24/7</div>
                  ) : hoursDisplay.length > 0 ? (
                    hoursDisplay.map((line, i) => <div key={i}>{line}</div>)
                  ) : (
                    <div className="text-secondary">No hours set</div>
                  )}
                </div>
              </div>
            </div>
          </section>

          <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface p-5">
            <h2 className="m-0 text-[14px] font-medium text-primary">
              Amenities
            </h2>
            {location.amenities.length === 0 ? (
              <div className="mt-4 text-[13px] text-secondary">
                No amenities listed
              </div>
            ) : (
              <div className="mt-4 grid grid-cols-2 gap-3">
                {location.amenities.map((amenityId) => {
                  const amenity = AMENITIES.find((a) => a.id === amenityId);
                  if (!amenity) return null;
                  return (
                    <div
                      key={amenityId}
                      className="flex items-center gap-2.5 text-[13px] text-primary"
                    >
                      <i
                        className={`ti ti-${amenity.icon} text-[16px] text-secondary`}
                      />
                      {amenity.label}
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        </div>

        <LocationEquipmentSection
          locationId={locationId}
          locationName={location.name}
          equipmentIds={location.equipmentIds}
          equipment={equipment}
          addEquipmentToLocation={addEquipmentToLocationAction}
          removeEquipmentFromLocation={removeEquipmentFromLocationAction}
          searchCatalog={searchCatalogAction}
          submitEquipment={submitEquipmentAction}
          bulkImportPreview={bulkImportPreviewAction}
          bulkImportConfirm={bulkImportConfirmAction}
        />
      </div>
    </main>
  );
}

function formatHours(location: {
  is24Hours: boolean;
  hours: Partial<Record<DayOfWeek, { open: string; close: string }>> | null;
}): string[] {
  if (location.is24Hours || !location.hours) return [];

  const hours = location.hours;
  const lines: string[] = [];

  // Group consecutive days with same hours
  const days: DayOfWeek[] = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
  let i = 0;

  while (i < days.length) {
    const day = days[i]!;
    const slot = hours[day];

    if (!slot) {
      i++;
      continue;
    }

    // Find consecutive days with same hours
    let endIdx = i;
    while (
      endIdx + 1 < days.length &&
      hours[days[endIdx + 1]!]?.open === slot.open &&
      hours[days[endIdx + 1]!]?.close === slot.close
    ) {
      endIdx++;
    }

    const startLabel = DAY_LABELS[days[i]!];
    const endLabel = DAY_LABELS[days[endIdx]!];
    const dayRange = i === endIdx ? startLabel : `${startLabel}-${endLabel}`;

    lines.push(
      `${dayRange}  ${formatTime(slot.open)} - ${formatTime(slot.close)}`
    );

    i = endIdx + 1;
  }

  return lines;
}

function formatTime(time: string): string {
  // Convert HH:mm to 12-hour format
  const [hourStr, minute] = time.split(":");
  const hour = parseInt(hourStr!, 10);
  const period = hour >= 12 ? "PM" : "AM";
  const displayHour = hour === 0 ? 12 : hour > 12 ? hour - 12 : hour;
  return `${displayHour}:${minute} ${period}`;
}
