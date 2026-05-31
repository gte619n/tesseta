"use client";

import { useEffect, useRef, useState } from 'react';
import type { DrugCategory, DrugForm } from '@/lib/types/medication';
import { CATEGORY_LABELS, FORM_LABELS } from '@/lib/types/medication';
import { useToast } from '@/components/ui/Toast';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSave: () => void;
  create: (data: {
    name: string;
    aliases: string[];
    category: DrugCategory;
    form: DrugForm;
    defaultUnit: string;
    commonDoses: string[];
    suggestedMarkers: string[];
    description: string | null;
  }) => Promise<void>;
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

export function AddDrugModal({ isOpen, onClose, onSave, create }: Props) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [aliasesText, setAliasesText] = useState('');
  const [category, setCategory] = useState<DrugCategory>('SUPPLEMENT');
  const [form, setForm] = useState<DrugForm>('TABLET');
  const [defaultUnit, setDefaultUnit] = useState('mg');
  const [commonDosesText, setCommonDosesText] = useState('');
  const [markersText, setMarkersText] = useState('');
  const [description, setDescription] = useState('');
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

  // Reset the form each time the modal opens.
  useEffect(() => {
    if (isOpen) {
      setName('');
      setAliasesText('');
      setCategory('SUPPLEMENT');
      setForm('TABLET');
      setDefaultUnit('mg');
      setCommonDosesText('');
      setMarkersText('');
      setDescription('');
    }
  }, [isOpen]);

  if (!isOpen) return null;

  function splitList(text: string): string[] {
    return text
      .split(',')
      .map(s => s.trim())
      .filter(Boolean);
  }

  async function handleSave() {
    if (!name.trim()) {
      toast.error('Name is required');
      return;
    }
    setIsSaving(true);
    try {
      await create({
        name: name.trim(),
        aliases: splitList(aliasesText),
        category,
        form,
        defaultUnit: defaultUnit.trim() || 'mg',
        commonDoses: splitList(commonDosesText),
        suggestedMarkers: splitList(markersText),
        description: description.trim() || null,
      });
      toast.success('Drug added');
      onSave();
    } catch (e) {
      toast.error('Failed to add drug', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-canvas/75 backdrop-blur-sm"
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
    >
      <div
        className="w-[560px] max-w-[92vw] max-h-[90vh] overflow-y-auto rounded-lg border border-border-default bg-surface p-6 shadow-[0_24px_64px_rgba(0,0,0,0.16)]"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-xl font-semibold text-primary">Add drug</h2>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Testosterone Cypionate"
              autoFocus
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

          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">
              Common doses (comma-separated)
            </label>
            <input
              value={commonDosesText}
              onChange={(e) => setCommonDosesText(e.target.value)}
              placeholder="100, 200, 250"
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">
              Suggested markers (comma-separated)
            </label>
            <input
              value={markersText}
              onChange={(e) => setMarkersText(e.target.value)}
              placeholder="Total Testosterone, Estradiol"
              className="w-full rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>

          <div>
            <label className="mb-1 block text-xs font-medium text-secondary">
              Description (optional)
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="w-full resize-y rounded-md border border-border-default bg-canvas px-3 py-2 text-sm text-primary focus:outline-none focus:ring-2 focus:ring-accent"
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
            {isSaving ? 'Adding…' : 'Add drug'}
          </button>
        </div>
      </div>
    </div>
  );
}
