"use client";

import { useState, useEffect, useRef } from 'react';
import type { AdminEquipment, SpecSchema, EquipmentCategory, EquipmentSpecs } from '@/lib/types/gym';
import { EQUIPMENT_CATEGORIES as CATEGORIES } from '@/lib/types/gym';
import { useToast } from '@/components/ui/Toast';

interface EditEquipmentModalProps {
  equipment: AdminEquipment;
  isOpen: boolean;
  onClose: () => void;
  onSave: () => void;
  update: (
    equipmentId: string,
    data: { name: string; category: string; subcategory: string; specSchema: SpecSchema; specs: EquipmentSpecs },
  ) => Promise<void>;
  // Optional. When provided, an extra "Regenerate image" button is rendered in
  // the footer. The parent decides what to do (typically close this modal and
  // open RegenerateImageModal).
  onRegenerate?: () => void;
}

export function EditEquipmentModal({ equipment, isOpen, onClose, onSave, update, onRegenerate }: EditEquipmentModalProps) {
  const toast = useToast();
  const [isSaving, setIsSaving] = useState(false);
  // Track whether the mousedown landed on the backdrop so we only close on a
  // true backdrop click. Without this, a text-selection drag that starts
  // inside the dialog and releases over the backdrop would close the modal.
  const downOnBackdropRef = useRef(false);

  function handleBackdropMouseDown(e: React.MouseEvent) {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }

  function handleBackdropClick(e: React.MouseEvent) {
    const downOnBackdrop = downOnBackdropRef.current;
    downOnBackdropRef.current = false;
    if (downOnBackdrop && e.target === e.currentTarget) {
      onClose();
    }
  }

  // Form state - use Record<string, unknown> for easier manipulation
  const [name, setName] = useState(equipment.name);
  const [category, setCategory] = useState(equipment.category);
  const [subcategory, setSubcategory] = useState(equipment.subcategory);
  const [specSchema, setSpecSchema] = useState(equipment.specSchema);
  const [specs, setSpecs] = useState<Record<string, unknown>>(equipment.specs as Record<string, unknown>);

  // Reset form when modal opens with new equipment
  useEffect(() => {
    if (isOpen) {
      setName(equipment.name);
      setCategory(equipment.category);
      setSubcategory(equipment.subcategory);
      setSpecSchema(equipment.specSchema);
      setSpecs(equipment.specs as Record<string, unknown>);
    }
  }, [isOpen, equipment]);

  if (!isOpen) return null;

  const availableSubcategories = CATEGORIES[category as EquipmentCategory] || [];

  async function handleSave() {
    if (!name.trim()) {
      toast.error('Name is required');
      return;
    }

    setIsSaving(true);
    try {
      await update(equipment.equipmentId, {
        name: name.trim(),
        category,
        subcategory,
        specSchema,
        specs: specs as EquipmentSpecs,
      });
      toast.success('Equipment updated');
      onSave();
    } catch (e) {
      toast.error('Failed to update equipment', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSaving(false);
    }
  }

  function updateSpec(key: string, value: unknown) {
    setSpecs(prev => ({ ...prev, [key]: value }));
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="w-[600px] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-xl font-semibold text-primary">Edit Equipment</h2>

        <div className="space-y-4">
          {/* Name */}
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Name
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          {/* Category */}
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Category
            </label>
            <select
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                // Reset subcategory when category changes
                const newSubcats = CATEGORIES[e.target.value as EquipmentCategory] || [];
                if (newSubcats.length > 0) {
                  setSubcategory(newSubcats[0]);
                }
              }}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {Object.keys(CATEGORIES).map(cat => (
                <option key={cat} value={cat}>{cat}</option>
              ))}
            </select>
          </div>

          {/* Subcategory */}
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Subcategory
            </label>
            <select
              value={subcategory}
              onChange={(e) => setSubcategory(e.target.value)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {availableSubcategories.map(subcat => (
                <option key={subcat} value={subcat}>{subcat}</option>
              ))}
            </select>
          </div>

          {/* Spec Schema */}
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Spec Schema
            </label>
            <select
              value={specSchema}
              onChange={(e) => {
                const newSchema = e.target.value as SpecSchema;
                setSpecSchema(newSchema);
                // Reset specs to default for new schema
                setSpecs(getDefaultSpecs(newSchema));
              }}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            >
              <option value="selectorized">Selectorized</option>
              <option value="plate_loaded">Plate-Loaded</option>
              <option value="bodyweight">Bodyweight</option>
              <option value="cable">Cable</option>
              <option value="cardio">Cardio</option>
            </select>
          </div>

          {/* Specs Editor */}
          <div className="rounded-md border border-border-default bg-canvas p-4">
            <h3 className="mb-3 text-sm font-medium text-primary">Specifications</h3>
            <SpecsEditor schema={specSchema} specs={specs} onChange={updateSpec} />
          </div>
        </div>

        <div className="mt-6 flex items-center justify-end gap-2">
          {onRegenerate && (
            <button
              type="button"
              onClick={onRegenerate}
              disabled={isSaving}
              className="mr-auto cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
            >
              Regenerate image
            </button>
          )}
          <button
            onClick={onClose}
            disabled={isSaving}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="cursor-pointer rounded-md bg-accent px-4 py-2 text-sm font-medium text-inverse hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSaving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

export function SpecsEditor({
  schema,
  specs,
  onChange,
}: {
  schema: SpecSchema;
  specs: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
}) {
  switch (schema) {
    case 'selectorized':
    case 'weight_set':
      return (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Min Weight (lb)
            </label>
            <input
              type="number"
              value={(specs.minWeight as number) || 0}
              onChange={(e) => onChange('minWeight', parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Max Weight (lb)
            </label>
            <input
              type="number"
              value={(specs.maxWeight as number) || 0}
              onChange={(e) => onChange('maxWeight', parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Increment (lb)
            </label>
            <input
              type="number"
              value={(specs.increment as number) || 0}
              onChange={(e) => onChange('increment', parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
        </div>
      );

    case 'plate_loaded':
      return (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Bar Weight (lb)
            </label>
            <input
              type="number"
              value={(specs.barWeight as number) || 0}
              onChange={(e) => onChange('barWeight', parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Available Plates (comma-separated, e.g., 2.5, 5, 10, 25, 35, 45)
            </label>
            <input
              type="text"
              value={(specs.availablePlates as number[] || []).join(', ')}
              onChange={(e) => {
                const plates = e.target.value
                  .split(',')
                  .map(s => parseFloat(s.trim()))
                  .filter(n => !isNaN(n));
                onChange('availablePlates', plates);
              }}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
        </div>
      );

    case 'bodyweight':
      return (
        <p className="text-sm text-secondary">
          No specifications required for bodyweight equipment.
        </p>
      );

    case 'cable':
      return (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Weight Stack (lb)
            </label>
            <input
              type="number"
              value={(specs.weightStack as number) || 0}
              onChange={(e) => onChange('weightStack', parseFloat(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Number of Stations
            </label>
            <input
              type="number"
              value={(specs.numStations as number) || 0}
              onChange={(e) => onChange('numStations', parseInt(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
        </div>
      );

    case 'cardio':
      return (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">
              Resistance Levels
            </label>
            <input
              type="number"
              value={(specs.resistanceLevels as number) || 0}
              onChange={(e) => onChange('resistanceLevels', parseInt(e.target.value) || 0)}
              className="w-full rounded-md border border-border-default bg-surface px-3 py-1.5 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="hasIncline"
              checked={(specs.hasIncline as boolean) || false}
              onChange={(e) => onChange('hasIncline', e.target.checked)}
              className="cursor-pointer rounded border-border-default text-accent focus:ring-2 focus:ring-accent"
            />
            <label htmlFor="hasIncline" className="cursor-pointer text-xs font-medium text-secondary">
              Has Incline
            </label>
          </div>
        </div>
      );
  }
}

export function getDefaultSpecs(schema: SpecSchema): Record<string, unknown> {
  switch (schema) {
    case 'selectorized':
      return { minWeight: 0, maxWeight: 0, increment: 0 };
    case 'plate_loaded':
      return { barWeight: 0, availablePlates: [] };
    case 'bodyweight':
      return {};
    case 'cable':
      return { weightStack: 0, numStations: 1 };
    case 'cardio':
      return { resistanceLevels: 0, hasIncline: false };
    case 'weight_set':
      return { minWeight: 0, maxWeight: 0, increment: 0 };
  }
}
