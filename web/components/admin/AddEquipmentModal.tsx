"use client";

import { useState, useEffect, useRef } from 'react';
import type { SpecSchema, EquipmentCategory, EquipmentSpecs } from '@/lib/types/gym';
import { EQUIPMENT_CATEGORIES as CATEGORIES } from '@/lib/types/gym';
import { useToast } from '@/components/ui/Toast';
import { SpecsEditor, getDefaultSpecs } from './EditEquipmentModal';

interface AddEquipmentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: () => void;
  create: (data: {
    name: string;
    category: string;
    subcategory: string;
    specSchema: SpecSchema;
    specs: EquipmentSpecs;
  }) => Promise<void>;
}

const DEFAULT_CATEGORY = Object.keys(CATEGORIES)[0] as EquipmentCategory;
const DEFAULT_SUBCATEGORY = CATEGORIES[DEFAULT_CATEGORY][0];
const DEFAULT_SCHEMA: SpecSchema = 'selectorized';

export function AddEquipmentModal({ isOpen, onClose, onSave, create }: AddEquipmentModalProps) {
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

  const [name, setName] = useState('');
  const [category, setCategory] = useState<string>(DEFAULT_CATEGORY);
  const [subcategory, setSubcategory] = useState<string>(DEFAULT_SUBCATEGORY);
  const [specSchema, setSpecSchema] = useState<SpecSchema>(DEFAULT_SCHEMA);
  const [specs, setSpecs] = useState<Record<string, unknown>>(getDefaultSpecs(DEFAULT_SCHEMA));

  // Reset the form each time the modal opens.
  useEffect(() => {
    if (isOpen) {
      setName('');
      setCategory(DEFAULT_CATEGORY);
      setSubcategory(DEFAULT_SUBCATEGORY);
      setSpecSchema(DEFAULT_SCHEMA);
      setSpecs(getDefaultSpecs(DEFAULT_SCHEMA));
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const availableSubcategories = CATEGORIES[category as EquipmentCategory] || [];

  async function handleSave() {
    if (!name.trim()) {
      toast.error('Name is required');
      return;
    }

    setIsSaving(true);
    try {
      await create({
        name: name.trim(),
        category,
        subcategory,
        specSchema,
        specs: specs as EquipmentSpecs,
      });
      toast.success('Equipment added');
      onSave();
    } catch (e) {
      toast.error('Failed to add equipment', {
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
        className="w-[600px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-xl font-semibold text-primary">Add Equipment</h2>

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
              placeholder="Hammer Strength Chest Press"
              autoFocus
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
            {isSaving ? 'Adding...' : 'Add equipment'}
          </button>
        </div>
      </div>
    </div>
  );
}
