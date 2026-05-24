"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

type Props = {
  locationId: string;
  locationName: string;
  onDelete: () => Promise<void>;
};

export function DeleteLocationButton({
  locationId,
  locationName,
  onDelete,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [isPending, startTransition] = useTransition();

  async function handleDelete() {
    const ok = await confirm({
      title: `Delete ${locationName}?`,
      description:
        "This will soft-delete the gym. You can restore it later from settings.",
      confirmLabel: "Delete",
      tone: "danger",
    });

    if (!ok) return;

    startTransition(async () => {
      try {
        await onDelete();
        toast.success("Gym deleted");
        router.push("/me/workouts/gyms");
        router.refresh();
      } catch (err) {
        toast.error("Failed to delete", {
          description: err instanceof Error ? err.message : "Try again",
        });
      }
    });
  }

  return (
    <button
      type="button"
      onClick={handleDelete}
      disabled={isPending}
      className="cursor-pointer rounded-md border-[0.5px] border-red-600/40 bg-red-50 px-3 py-1.5 text-[12px] font-medium text-red-600 hover:bg-red-100 disabled:opacity-50"
      title="Delete gym"
    >
      <i className="ti ti-trash" />
    </button>
  );
}
