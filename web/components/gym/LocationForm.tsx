"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { Route } from "next";
import type { Location, DayOfWeek, HoursSlot } from "@/lib/types/gym";
import { HoursEditor } from "./HoursEditor";
import { AmenitiesChecklist } from "./AmenitiesChecklist";
import { useToast } from "@/components/ui/Toast";

type Props = {
  initialData?: Location;
  onSubmit: (data: FormData) => Promise<Location>;
  cancelHref: string;
};

export function LocationForm({ initialData, onSubmit, cancelHref }: Props) {
  const router = useRouter();
  const toast = useToast();
  const [isPending, startTransition] = useTransition();

  const [name, setName] = useState(initialData?.name || "");
  const [address, setAddress] = useState(initialData?.address || "");
  const [is24Hours, setIs24Hours] = useState(initialData?.is24Hours || false);
  const [hours, setHours] = useState<Partial<Record<DayOfWeek, HoursSlot>> | null>(
    initialData?.hours || null
  );
  const [amenities, setAmenities] = useState<string[]>(
    initialData?.amenities || []
  );

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();

    if (!name.trim()) {
      toast.error("Name is required");
      return;
    }

    const formData = new FormData();
    formData.set("name", name.trim());
    formData.set("address", address.trim() || "");
    formData.set("is24Hours", String(is24Hours));
    formData.set("hours", JSON.stringify(is24Hours ? null : hours));
    formData.set("amenities", JSON.stringify(amenities));

    startTransition(async () => {
      try {
        const location = await onSubmit(formData);
        toast.success(initialData ? "Gym updated" : "Gym created");
        router.push(`/me/workouts/gyms/${location.locationId}`);
      } catch (err) {
        toast.error("Something went wrong", {
          description: err instanceof Error ? err.message : "Try again",
        });
      }
    });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      <label className="flex flex-col gap-1">
        <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          Name *
        </span>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
          placeholder="e.g. Downtown Fitness"
          className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          Address
        </span>
        <input
          type="text"
          value={address}
          onChange={(e) => setAddress(e.target.value)}
          placeholder="123 Main St, City, ST 12345"
          className="rounded-md border-[0.5px] border-border-default bg-canvas px-2.5 py-2 text-[13px] text-primary"
        />
      </label>

      <div className="space-y-3">
        <label className="flex items-center gap-2.5 cursor-pointer">
          <input
            type="checkbox"
            checked={is24Hours}
            onChange={(e) => setIs24Hours(e.target.checked)}
            className="h-4 w-4 cursor-pointer rounded border-border-default accent-accent focus:ring-accent focus:ring-offset-0"
          />
          <span className="text-[13px] text-primary">Open 24/7</span>
        </label>

        {!is24Hours && (
          <HoursEditor value={hours} onChange={setHours} disabled={is24Hours} />
        )}
      </div>

      <div className="space-y-3">
        <span className="font-mono text-[10px] uppercase tracking-[0.06em] text-tertiary">
          Amenities
        </span>
        <AmenitiesChecklist value={amenities} onChange={setAmenities} />
      </div>

      <div className="flex justify-end gap-2 pt-3">
        <button
          type="button"
          onClick={() => router.push(cancelHref as Route)}
          className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isPending}
          className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-inverse disabled:opacity-50"
        >
          {isPending ? "Saving…" : initialData ? "Save changes" : "Create gym"}
        </button>
      </div>
    </form>
  );
}
