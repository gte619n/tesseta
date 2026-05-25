"use client";

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { Equipment, SpecSchema } from '@/lib/types/gym';
import { approveEquipment, rejectEquipment, regenerateEquipmentImage } from '@/lib/gym-api';
import { useConfirm } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/components/ui/Toast';
import { EditEquipmentModal } from './EditEquipmentModal';

interface PendingEquipmentCardProps {
  equipment: Equipment;
}

export function PendingEquipmentCard({ equipment }: PendingEquipmentCardProps) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isRegenerating, setIsRegenerating] = useState(false);

  async function handleApprove() {
    const ok = await confirm({
      title: 'Approve Equipment',
      description: `This will add "${equipment.name}" to the catalog and generate an image.`,
      confirmLabel: 'Approve',
    });

    if (!ok) return;

    setIsProcessing(true);
    try {
      await approveEquipment(equipment.equipmentId);
      toast.success('Equipment approved', {
        description: 'Image generation started',
      });
      router.refresh();
    } catch (e) {
      toast.error('Failed to approve equipment', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleReject() {
    const ok = await confirm({
      title: 'Reject Equipment',
      description: `Are you sure you want to reject "${equipment.name}"?`,
      confirmLabel: 'Reject',
      tone: 'danger',
    });

    if (!ok) return;

    setIsProcessing(true);
    try {
      await rejectEquipment(equipment.equipmentId);
      toast.success('Equipment rejected');
      router.refresh();
    } catch (e) {
      toast.error('Failed to reject equipment', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsProcessing(false);
    }
  }

  async function handleRegenerate() {
    setIsRegenerating(true);
    try {
      await regenerateEquipmentImage(equipment.equipmentId);
      toast.success('Image regeneration started');
      router.refresh();
    } catch (e) {
      toast.error('Failed to start regeneration', {
        description: e instanceof Error ? e.message : 'Unknown error',
      });
    } finally {
      setIsRegenerating(false);
    }
  }

  function handleEditSave() {
    setIsEditOpen(false);
    router.refresh();
  }

  return (
    <>
      <div className="rounded-lg border border-border-default bg-surface p-6">
        <div className="mb-4">
          <h3 className="text-lg font-medium text-primary">{equipment.name}</h3>
          <div className="mt-2 space-y-1 text-sm text-secondary">
            <p>
              Submitted by: <span className="font-mono">{equipment.contributorId || 'Unknown'}</span>
            </p>
            <p>
              Category: {equipment.category} &gt; {equipment.subcategory}
            </p>
            <p>
              Schema: {formatSchemaName(equipment.specSchema)}
            </p>
            <p>
              Specs: {formatSpecs(equipment.specSchema, equipment.specs)}
            </p>
          </div>
        </div>

        <div className="mb-4">
          <ImageStatusBadge
            status={equipment.imageStatus}
            onRegenerate={handleRegenerate}
            isRegenerating={isRegenerating}
          />
        </div>

        <div className="flex gap-2">
          <button
            onClick={handleApprove}
            disabled={isProcessing}
            className="cursor-pointer rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Approve
          </button>
          <button
            onClick={() => setIsEditOpen(true)}
            disabled={isProcessing}
            className="cursor-pointer rounded-md border border-border-default bg-canvas px-4 py-2 text-sm font-medium text-primary hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          >
            Edit
          </button>
          <button
            onClick={handleReject}
            disabled={isProcessing}
            className="cursor-pointer rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Reject
          </button>
        </div>
      </div>

      <EditEquipmentModal
        equipment={equipment}
        isOpen={isEditOpen}
        onClose={() => setIsEditOpen(false)}
        onSave={handleEditSave}
      />
    </>
  );
}

function ImageStatusBadge({
  status,
  onRegenerate,
  isRegenerating,
}: {
  status: 'pending' | 'generated' | 'failed';
  onRegenerate: () => void;
  isRegenerating: boolean;
}) {
  switch (status) {
    case 'pending':
      return (
        <div className="flex items-center gap-2 text-sm text-yellow-600">
          <span className="inline-block h-2 w-2 rounded-full bg-yellow-600" />
          Pending - will generate on approval
        </div>
      );
    case 'generated':
      return (
        <div className="flex items-center gap-2 text-sm text-green-600">
          <span className="inline-block h-2 w-2 rounded-full bg-green-600" />
          Generated
        </div>
      );
    case 'failed':
      return (
        <div className="flex items-center gap-2 text-sm">
          <span className="inline-block h-2 w-2 rounded-full bg-red-600" />
          <span className="text-red-600">Failed</span>
          <button
            onClick={onRegenerate}
            disabled={isRegenerating}
            className="ml-2 cursor-pointer text-sm text-accent underline hover:text-accent/80 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isRegenerating ? 'Regenerating...' : 'Retry'}
          </button>
        </div>
      );
  }
}

function formatSchemaName(schema: SpecSchema): string {
  const names: Record<SpecSchema, string> = {
    selectorized: 'Selectorized',
    plate_loaded: 'Plate-Loaded',
    bodyweight: 'Bodyweight',
    cable: 'Cable',
    cardio: 'Cardio',
    weight_set: 'Weight Set',
  };
  return names[schema];
}

function formatSpecs(specSchema: SpecSchema, specs: Record<string, unknown>): string {
  switch (specSchema) {
    case 'selectorized':
    case 'weight_set':
      return `Weight: ${specs.minWeight}-${specs.maxWeight}lb (${specs.increment}lb increments)`;
    case 'plate_loaded': {
      const plates = (specs.availablePlates as number[])?.join(', ') || 'None';
      return `Bar: ${specs.barWeight}lb, Plates: ${plates}`;
    }
    case 'bodyweight':
      return 'No specs';
    case 'cable':
      return `Stack: ${specs.weightStack}lb, Stations: ${specs.numStations}`;
    case 'cardio':
      return `Levels: ${specs.resistanceLevels}, Incline: ${specs.hasIncline ? 'Yes' : 'No'}`;
  }
}
