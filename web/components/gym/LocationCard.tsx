import Image from "next/image";
import Link from "next/link";
import type { Location } from "@/lib/types/gym";
import { AMENITIES } from "@/lib/types/gym";

type Props = {
  location: Location;
};

export function LocationCard({ location }: Props) {
  const equipmentCount = location.equipmentIds.length;
  const displayAmenities = location.amenities.slice(0, 4);

  return (
    <Link
      href={`/me/workouts/gyms/${location.locationId}`}
      className="block rounded-[14px] border-[0.5px] border-border-default bg-surface shadow-sm transition-shadow hover:shadow-md"
    >
      <div className="relative aspect-[16/9] overflow-hidden rounded-t-[13px] bg-canvas">
        {location.coverPhotoUrl ? (
          <Image
            src={location.coverPhotoUrl}
            alt={location.name}
            fill
            sizes="(max-width: 768px) 100vw, 400px"
            className="object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-canvas-muted to-canvas">
            <span className="font-mono text-[48px] text-quaternary opacity-40">
              {location.name.charAt(0).toUpperCase()}
            </span>
          </div>
        )}
      </div>

      <div className="p-4">
        <div className="flex items-start gap-2">
          <h3 className="flex-1 text-[15px] font-medium text-primary">
            {location.name}
          </h3>
          {location.isDefault && (
            <span className="mt-0.5 text-[16px]" title="Default gym">
              ★
            </span>
          )}
        </div>

        <p className="mt-1 text-[12px] text-secondary">
          {location.address || "No address"}
        </p>

        <div className="mt-2 text-[12px] text-tertiary">
          {equipmentCount} equipment {equipmentCount === 1 ? "item" : "items"}
        </div>

        {displayAmenities.length > 0 && (
          <div className="mt-2 flex gap-1">
            {displayAmenities.map((amenityId) => {
              const amenity = AMENITIES.find((a) => a.id === amenityId);
              if (!amenity) return null;
              return (
                <span
                  key={amenityId}
                  className="inline-flex h-6 w-6 items-center justify-center rounded-md bg-canvas text-[12px]"
                  title={amenity.label}
                >
                  <i className={`ti ti-${amenity.icon}`} />
                </span>
              );
            })}
          </div>
        )}
      </div>
    </Link>
  );
}
