"use client";

import { useState } from "react";
import { CategorySelector } from "./CategorySelector";
import { EquipmentSpecsForm } from "./EquipmentSpecsForm";
import { useToast } from "@/components/ui/Toast";
import type { CreateEquipmentRequest, Equipment, SpecSchema, EquipmentSpecs } from "@/lib/types/gym";

interface EquipmentSubmitFormProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (equipment: Equipment) => void;
  locationId?: string;
  submitEquipment: (data: CreateEquipmentRequest) => Promise<Equipment>;
}

interface FormState {
  name: string;
  category: string;
  subcategory: string;
  proposeNewSubcategory: boolean;
  newSubcategoryName: string;
  specs: Record<string, unknown>;
}

function getSpecSchema(category: string, subcategory: string): SpecSchema {
  if (category === "Machines - Cardio") return "cardio";
  if (category === "Cable Systems") return "cable";
  if (category === "Bodyweight") return "bodyweight";

  // Check if subcategory implies plate-loaded
  const plateLoadedSubs = ["Barbells", "Dumbbells", "Weight Plates", "Racks", "Stations"];
  if (plateLoadedSubs.includes(subcategory)) return "plate_loaded";

  // Default for machines
  if (category.includes("Machines")) return "selectorized";

  return "bodyweight";
}

function validateForm(form: FormState): string | null {
  if (!form.name.trim()) return "Equipment name is required";
  if (!form.category) return "Category is required";
  if (!form.subcategory && !form.proposeNewSubcategory) return "Subcategory is required";
  if (form.proposeNewSubcategory && !form.newSubcategoryName.trim()) {
    return "New subcategory name is required";
  }
  return null;
}

export function EquipmentSubmitForm({
  isOpen,
  onClose,
  onSubmit,
  submitEquipment,
}: EquipmentSubmitFormProps) {
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState<FormState>({
    name: "",
    category: "",
    subcategory: "",
    proposeNewSubcategory: false,
    newSubcategoryName: "",
    specs: {},
  });

  function resetForm() {
    setForm({
      name: "",
      category: "",
      subcategory: "",
      proposeNewSubcategory: false,
      newSubcategoryName: "",
      specs: {},
    });
  }

  function handleClose() {
    resetForm();
    onClose();
  }

  async function handleSubmit() {
    const error = validateForm(form);
    if (error) {
      toast.error(error);
      return;
    }

    setSubmitting(true);
    try {
      const finalSubcategory = form.proposeNewSubcategory
        ? form.newSubcategoryName
        : form.subcategory;
      const specSchema = getSpecSchema(form.category, finalSubcategory);

      const equipment = await submitEquipment({
        name: form.name,
        category: form.category,
        subcategory: finalSubcategory,
        specSchema,
        specs: form.specs as EquipmentSpecs,
      });

      toast.success("Equipment submitted successfully");
      onSubmit(equipment);
      handleClose();
    } catch (e) {
      toast.error("Failed to submit equipment", {
        description: e instanceof Error ? e.message : "Please try again",
      });
    } finally {
      setSubmitting(false);
    }
  }

  if (!isOpen) return null;

  const currentSpecSchema = form.category
    ? getSpecSchema(
        form.category,
        form.proposeNewSubcategory ? form.newSubcategoryName : form.subcategory
      )
    : "bodyweight";

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center bg-canvas/75 backdrop-blur-sm">
      <div className="flex max-h-[90vh] w-[90vw] max-w-[600px] flex-col overflow-hidden rounded-[14px] border border-border-default bg-surface shadow-[0_24px_64px_rgba(0,0,0,0.16)]">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-default px-6 py-4">
          <h2 className="text-[18px] font-semibold text-primary">Submit New Equipment</h2>
          <button
            type="button"
            onClick={handleClose}
            className="cursor-pointer rounded-md px-2 py-1 font-mono text-[20px] leading-none text-tertiary hover:text-primary"
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {/* Form */}
        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="space-y-6">
            {/* Equipment Name */}
            <div>
              <label htmlFor="equipmentName" className="mb-1.5 block text-[13px] font-medium text-primary">
                Equipment Name *
              </label>
              <input
                id="equipmentName"
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="e.g., Hammer Strength Incline Press"
                className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-[13px] text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>

            {/* Category & Subcategory */}
            <CategorySelector
              category={form.category}
              subcategory={form.subcategory}
              onCategoryChange={(category) =>
                setForm({ ...form, category, subcategory: "", proposeNewSubcategory: false, newSubcategoryName: "", specs: {} })
              }
              onSubcategoryChange={(subcategory) =>
                setForm({ ...form, subcategory, specs: {} })
              }
              proposeNew={form.proposeNewSubcategory}
              onProposeNewChange={(proposeNewSubcategory) =>
                setForm({ ...form, proposeNewSubcategory, specs: {} })
              }
              newSubcategoryName={form.newSubcategoryName}
              onNewSubcategoryNameChange={(newSubcategoryName) =>
                setForm({ ...form, newSubcategoryName, specs: {} })
              }
            />

            {/* Specs Form */}
            {form.category && (form.subcategory || form.proposeNewSubcategory) && (
              <EquipmentSpecsForm
                specSchema={currentSpecSchema}
                value={form.specs}
                onChange={(specs) => setForm({ ...form, specs })}
              />
            )}

            {/* Info Text */}
            <div className="rounded-md bg-canvas px-4 py-3">
              <p className="text-[12px] text-tertiary">
                This equipment will be added to your gym immediately. It will be reviewed and may be
                promoted to the catalog.
              </p>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="border-t border-border-default px-6 py-4">
          <div className="flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={handleClose}
              disabled={submitting}
              className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={submitting}
              className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[13px] font-medium text-white hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? "Submitting..." : "Submit"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
