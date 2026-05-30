"use client";

import { AMENITIES } from "@/lib/types/gym";

type Props = {
  value: string[];
  onChange: (amenities: string[]) => void;
};

export function AmenitiesChecklist({ value, onChange }: Props) {
  function toggle(id: string) {
    if (value.includes(id)) {
      onChange(value.filter((a) => a !== id));
    } else {
      onChange([...value, id]);
    }
  }

  return (
    <div className="grid grid-cols-2 gap-3">
      {AMENITIES.map((amenity) => {
        const checked = value.includes(amenity.id);
        return (
          <label
            key={amenity.id}
            className="flex cursor-pointer items-center gap-2.5 rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2.5 transition-colors hover:bg-canvas-muted"
          >
            <input
              type="checkbox"
              checked={checked}
              onChange={() => toggle(amenity.id)}
              className="h-4 w-4 cursor-pointer rounded border-border-default accent-accent focus:ring-accent focus:ring-offset-0"
            />
            <i className={`ti ti-${amenity.icon} text-[16px] text-secondary`} />
            <span className="flex-1 text-[13px] text-primary">
              {amenity.label}
            </span>
          </label>
        );
      })}
    </div>
  );
}
