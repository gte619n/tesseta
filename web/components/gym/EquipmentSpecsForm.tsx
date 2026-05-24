"use client";

import type { SpecSchema } from "@/lib/types/gym";

interface EquipmentSpecsFormProps {
  specSchema: SpecSchema;
  value: Record<string, unknown>;
  onChange: (specs: Record<string, unknown>) => void;
}

export function EquipmentSpecsForm({ specSchema, value, onChange }: EquipmentSpecsFormProps) {
  function updateSpec(key: string, val: unknown) {
    onChange({ ...value, [key]: val });
  }

  if (specSchema === "bodyweight") {
    return (
      <div className="rounded-md border border-border-default bg-surface px-4 py-3">
        <p className="text-[13px] font-medium text-primary">Specs (Bodyweight)</p>
        <p className="mt-1 text-[12px] text-tertiary">No additional specifications required</p>
      </div>
    );
  }

  if (specSchema === "selectorized") {
    const minWeight = typeof value.minWeight === "number" ? value.minWeight : "";
    const maxWeight = typeof value.maxWeight === "number" ? value.maxWeight : "";
    const increment = typeof value.increment === "number" ? value.increment : "";

    return (
      <div className="space-y-3 rounded-md border border-border-default bg-surface px-4 py-3">
        <p className="text-[13px] font-medium text-primary">Specs (Selectorized)</p>
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label htmlFor="minWeight" className="mb-1 block text-[12px] text-secondary">
              Min Weight (lb)
            </label>
            <input
              id="minWeight"
              type="number"
              value={minWeight}
              onChange={(e) => updateSpec("minWeight", parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="maxWeight" className="mb-1 block text-[12px] text-secondary">
              Max Weight (lb)
            </label>
            <input
              id="maxWeight"
              type="number"
              value={maxWeight}
              onChange={(e) => updateSpec("maxWeight", parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="increment" className="mb-1 block text-[12px] text-secondary">
              Increment (lb)
            </label>
            <input
              id="increment"
              type="number"
              value={increment}
              onChange={(e) => updateSpec("increment", parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
        </div>
      </div>
    );
  }

  if (specSchema === "plate_loaded") {
    const barWeight = typeof value.barWeight === "number" ? value.barWeight : "";
    const availablePlates = Array.isArray(value.availablePlates)
      ? value.availablePlates.join(", ")
      : "";

    return (
      <div className="space-y-3 rounded-md border border-border-default bg-surface px-4 py-3">
        <p className="text-[13px] font-medium text-primary">Specs (Plate-Loaded)</p>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="barWeight" className="mb-1 block text-[12px] text-secondary">
              Bar Weight (lb)
            </label>
            <input
              id="barWeight"
              type="number"
              value={barWeight}
              onChange={(e) => updateSpec("barWeight", parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="availablePlates" className="mb-1 block text-[12px] text-secondary">
              Available Plates (lb)
            </label>
            <input
              id="availablePlates"
              type="text"
              value={availablePlates}
              onChange={(e) => {
                const plates = e.target.value
                  .split(",")
                  .map((s) => s.trim())
                  .filter((s) => s !== "")
                  .map((s) => parseFloat(s))
                  .filter((n) => !isNaN(n));
                updateSpec("availablePlates", plates);
              }}
              placeholder="e.g., 2.5, 5, 10, 25, 35, 45"
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
        </div>
      </div>
    );
  }

  if (specSchema === "cable") {
    const weightStack = typeof value.weightStack === "number" ? value.weightStack : "";
    const numStations = typeof value.numStations === "number" ? value.numStations : "";

    return (
      <div className="space-y-3 rounded-md border border-border-default bg-surface px-4 py-3">
        <p className="text-[13px] font-medium text-primary">Specs (Cable)</p>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="weightStack" className="mb-1 block text-[12px] text-secondary">
              Weight Stack (lb)
            </label>
            <input
              id="weightStack"
              type="number"
              value={weightStack}
              onChange={(e) => updateSpec("weightStack", parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="numStations" className="mb-1 block text-[12px] text-secondary">
              Number of Stations
            </label>
            <input
              id="numStations"
              type="number"
              value={numStations}
              onChange={(e) => updateSpec("numStations", parseInt(e.target.value, 10) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
        </div>
      </div>
    );
  }

  if (specSchema === "cardio") {
    const resistanceLevels = typeof value.resistanceLevels === "number" ? value.resistanceLevels : "";
    const hasIncline = typeof value.hasIncline === "boolean" ? value.hasIncline : false;

    return (
      <div className="space-y-3 rounded-md border border-border-default bg-surface px-4 py-3">
        <p className="text-[13px] font-medium text-primary">Specs (Cardio)</p>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="resistanceLevels" className="mb-1 block text-[12px] text-secondary">
              Resistance Levels
            </label>
            <input
              id="resistanceLevels"
              type="number"
              value={resistanceLevels}
              onChange={(e) => updateSpec("resistanceLevels", parseInt(e.target.value, 10) || 0)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div className="flex items-end pb-2">
            <label className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={hasIncline}
                onChange={(e) => updateSpec("hasIncline", e.target.checked)}
                className="h-4 w-4 cursor-pointer rounded border-border-default text-accent focus:ring-2 focus:ring-accent"
              />
              <span className="text-[13px] text-secondary">Has Incline</span>
            </label>
          </div>
        </div>
      </div>
    );
  }

  return null;
}
