"use client";

import { useState, useEffect, useCallback } from "react";
import { EquipmentSearch } from "./EquipmentSearch";
import { EquipmentCategoryFilter } from "./EquipmentCategoryFilter";
import { EquipmentCard } from "./EquipmentCard";
import { EquipmentSubmitForm } from "./EquipmentSubmitForm";
import { getEquipmentCatalog, addEquipmentToLocation, removeEquipmentFromLocation } from "@/lib/gym-api";
import { EQUIPMENT_CATEGORIES } from "@/lib/types/gym";
import { useToast } from "@/components/ui/Toast";
import type { Equipment } from "@/lib/types/gym";

interface EquipmentCatalogProps {
  locationId: string;
  locationName: string;
  currentEquipmentIds: string[];
  isOpen: boolean;
  onClose: () => void;
  onSave: (equipmentIds: string[]) => Promise<void>;
}

export function EquipmentCatalog({
  locationId,
  locationName,
  currentEquipmentIds,
  isOpen,
  onClose,
  onSave,
}: EquipmentCatalogProps) {
  const toast = useToast();
  const [equipment, setEquipment] = useState<Equipment[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [search, setSearch] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [selectedSubcategory, setSelectedSubcategory] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set(currentEquipmentIds));
  const [submitFormOpen, setSubmitFormOpen] = useState(false);

  const fetchEquipment = useCallback(async () => {
    setLoading(true);
    try {
      const params = {
        search: search || undefined,
        category: selectedCategory || undefined,
        subcategory: selectedSubcategory || undefined,
      };
      const data = await getEquipmentCatalog(params);
      setEquipment(data);
    } catch (error) {
      toast.error("Failed to load equipment", {
        description: error instanceof Error ? error.message : "Please try again",
      });
    } finally {
      setLoading(false);
    }
  }, [search, selectedCategory, selectedSubcategory, toast]);

  useEffect(() => {
    if (isOpen) {
      fetchEquipment();
    }
  }, [isOpen, fetchEquipment]);

  // Reset selected IDs when modal opens
  useEffect(() => {
    if (isOpen) {
      setSelectedIds(new Set(currentEquipmentIds));
    }
  }, [isOpen, currentEquipmentIds]);

  function toggleEquipment(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  async function handleSave() {
    setSaving(true);
    try {
      const toAdd = [...selectedIds].filter((id) => !currentEquipmentIds.includes(id));
      const toRemove = currentEquipmentIds.filter((id) => !selectedIds.has(id));

      // Call API for each change
      await Promise.all([
        ...toAdd.map((id) => addEquipmentToLocation(locationId, id)),
        ...toRemove.map((id) => removeEquipmentFromLocation(locationId, id)),
      ]);

      toast.success("Equipment updated");
      await onSave([...selectedIds]);
      onClose();
    } catch (error) {
      toast.error("Failed to save changes", {
        description: error instanceof Error ? error.message : "Please try again",
      });
    } finally {
      setSaving(false);
    }
  }

  function handleSubmitEquipment(newEquipment: Equipment) {
    // Add the newly submitted equipment to the list and select it
    setEquipment((prev) => [newEquipment, ...prev]);
    setSelectedIds((prev) => new Set([...prev, newEquipment.equipmentId]));
    // Re-fetch to get the updated list
    fetchEquipment();
  }

  if (!isOpen) return null;

  const selectedCount = selectedIds.size;
  const hasChanges = selectedCount !== currentEquipmentIds.length ||
    [...selectedIds].some((id) => !currentEquipmentIds.includes(id));

  return (
    <>
      <EquipmentSubmitForm
        isOpen={submitFormOpen}
        onClose={() => setSubmitFormOpen(false)}
        onSubmit={handleSubmitEquipment}
        locationId={locationId}
      />
      <div className="fixed inset-0 z-[100] flex items-center justify-center bg-canvas/75 backdrop-blur-sm">
        <div className="flex h-[90vh] max-h-[900px] w-[90vw] max-w-[1200px] flex-col overflow-hidden rounded-[14px] border border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-default px-6 py-4">
          <h2 className="text-[18px] font-semibold text-primary">
            Add Equipment to {locationName}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="cursor-pointer rounded-md px-2 py-1 font-mono text-[20px] leading-none text-tertiary hover:text-primary"
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {/* Search and Filters */}
        <div className="space-y-3 border-b border-border-default px-6 py-4">
          <EquipmentSearch value={search} onChange={setSearch} />
          <EquipmentCategoryFilter
            categories={EQUIPMENT_CATEGORIES}
            selectedCategory={selectedCategory}
            selectedSubcategory={selectedSubcategory}
            onCategoryChange={setSelectedCategory}
            onSubcategoryChange={setSelectedSubcategory}
          />
        </div>

        {/* Equipment Grid */}
        <div className="flex-1 overflow-y-auto px-6 py-6">
          {loading ? (
            <div className="flex h-full items-center justify-center text-[13px] text-tertiary">
              Loading equipment...
            </div>
          ) : equipment.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
              <p className="text-[13px] text-tertiary">No equipment found</p>
              {(search || selectedCategory) && (
                <button
                  type="button"
                  onClick={() => {
                    setSearch("");
                    setSelectedCategory(null);
                    setSelectedSubcategory(null);
                  }}
                  className="text-[12px] text-accent hover:underline"
                >
                  Clear filters
                </button>
              )}
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
              {equipment.map((item) => (
                <EquipmentCard
                  key={item.equipmentId}
                  equipment={item}
                  isSelected={selectedIds.has(item.equipmentId)}
                  onToggle={() => toggleEquipment(item.equipmentId)}
                />
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="border-t border-border-default px-6 py-4">
          <div className="mb-3 text-center">
            <p className="text-[12px] text-tertiary">
              Can&apos;t find what you need?{" "}
              <button
                type="button"
                onClick={() => setSubmitFormOpen(true)}
                className="text-accent hover:underline"
              >
                Submit New Equipment
              </button>
            </p>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-[13px] text-secondary">
              Selected: {selectedCount} item{selectedCount !== 1 ? "s" : ""}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onClose}
                className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary hover:bg-surface"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSave}
                disabled={!hasChanges || saving}
                className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-white hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {saving ? "Saving..." : "Save"}
              </button>
            </div>
          </div>
        </div>
        </div>
      </div>
    </>
  );
}
