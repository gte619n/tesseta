"use client";

import { useEffect, useState } from 'react';
import type { Drug, DrugCategory, DrugForm } from '@/lib/types/medication';
import { CATEGORY_LABELS, FORM_LABELS } from '@/lib/types/medication';
import { useToast } from '@/components/ui/Toast';

interface Props {
  drug: Drug;
  isOpen: boolean;
  onClose: () => void;
  onSave: () => void;
  update: (
    drugId: string,
    data: {
      name: string;
      aliases: string[];
      category: DrugCategory;
      form: DrugForm;
      defaultUnit: string;
    },
  ) => Promise<void>;
}

const CATEGORIES: DrugCategory[] = ['PRESCRIPTION', 'SUPPLEMENT', 'OTC', 'PEPTIDE', 'TOPICAL'];
const FORMS: DrugForm[] = [
  'INJECTABLE_VIAL',
  'TABLET',
  'CAPSULE',
  'SOFTGEL',
  'CREAM',
  'PATCH',
  'LIQUID',
  'POWDER',
];

export function EditDrugModal({ drug, isOpen, onClose, onSave, update }: Props) {
  const toast = useToast();
  const [name, setName] = useState(drug.name);
  const [aliasesText, setAliasesText] = useState(drug.aliases.join(', '));
  const [category, setCategory] = useState<DrugCategory>(drug.category);
  const [form, setForm] = useState<DrugForm>(drug.form);
  const [defaultUnit, setDefaultUnit] = useState(drug.defaultUnit);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setName(drug.name);
      setAliasesText(drug.aliases.join(', '));
      setCategory(drug.category);
      setForm(drug.form);
      setDefaultUnit(drug.defaultUnit);
    }
  }, [isOpen, drug]);

  if (!isOpen) return null;

  async function handleSave() {
    if (!name.trim()) {
      toast.error('Name is required');
      return;
    }
    setIsSaving(true);
    try {
      const aliases = aliasesText
        .split(',')
        .map(s => s.trim())
        .filter(Boolean);
      await update(drug.drugId, {
        name: name.trim(),
        aliases,
        category,
        form,
        defaultUnit: defaultUnit.trim() || 'mg',
      });
      toast.success('Drug updated');
      onSave();
    } catch (e) {
      toast.error('Failed to update drug', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="w-[560px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-xl font-semibold text-primary">Edit drug</h2>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">
              Aliases (comma-separated)
            </label>
            <input
              value={aliasesText}
              onChange={(e) => setAliasesText(e.target.value)}
              placeholder="Test Cyp, Depo-Testosterone"
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-secondary">Category</label>
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value as DrugCategory)}
                className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              >
                {CATEGORIES.map(c => (
                  <option key={c} value={c}>
                    {CATEGORY_LABELS[c]}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-secondary">Form</label>
              <select
                value={form}
                onChange={(e) => setForm(e.target.value as DrugForm)}
                className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
              >
                {FORMS.map(f => (
                  <option key={f} value={f}>
                    {FORM_LABELS[f]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">Default unit</label>
            <input
              value={defaultUnit}
              onChange={(e) => setDefaultUnit(e.target.value)}
              placeholder="mg"
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-2">
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
            {isSaving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}
