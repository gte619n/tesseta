"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { useToast } from "@/components/ui/Toast";

type Props = {
  locationId: string;
  isDefault: boolean;
  onSetDefault: () => Promise<void>;
};

export function SetDefaultButton({ locationId, isDefault, onSetDefault }: Props) {
  const router = useRouter();
  const toast = useToast();
  const [isPending, startTransition] = useTransition();

  async function handleSetDefault() {
    startTransition(async () => {
      try {
        await onSetDefault();
        toast.success("Default gym updated");
        router.refresh();
      } catch (err) {
        toast.error("Failed to set default", {
          description: err instanceof Error ? err.message : "Try again",
        });
      }
    });
  }

  return (
    <button
      type="button"
      onClick={handleSetDefault}
      disabled={isDefault || isPending}
      className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[12px] font-medium text-primary hover:bg-canvas-muted disabled:opacity-50 disabled:cursor-not-allowed"
    >
      {isDefault ? "Default gym" : "Set as default"}
    </button>
  );
}
