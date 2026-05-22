"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

// Delete-scan button. Uses our own ConfirmDialog (matches the design
// tokens) and Toast for the success / failure feedback — no more
// native window.confirm() or alert() pop-ups.
export function DeleteDexaButton({
  scanDate,
  deleteAction,
}: {
  scanDate: string | null;
  deleteAction: () => Promise<void>;
}) {
  const router = useRouter();
  const confirm = useConfirm();
  const { toast } = useToastApi();
  const [pending, startTransition] = useTransition();

  async function onClick() {
    const label = scanDate ? `the scan from ${scanDate}` : "this scan";
    const ok = await confirm({
      title: "Delete this DEXA scan?",
      description: `${label.charAt(0).toUpperCase() + label.slice(1)} will be removed from your record. This cannot be undone.`,
      confirmLabel: "Delete",
      tone: "danger",
    });
    if (!ok) return;

    startTransition(async () => {
      try {
        await deleteAction();
      } catch (err) {
        toast.error("Couldn’t delete the scan", {
          description: err instanceof Error ? err.message : "Try again.",
        });
        return;
      }
      toast.success("Scan deleted");
      router.push("/me/body-composition");
      router.refresh();
    });
  }

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={pending}
      className="cursor-pointer rounded-md border-[0.5px] border-red-600/30 bg-canvas px-3 py-1.5 text-[12px] font-medium text-red-600 hover:bg-red-600/5 disabled:opacity-60"
    >
      {pending ? "Deleting…" : "Delete scan"}
    </button>
  );
}

// Thin wrapper so the JSX above reads `toast.success(...)` instead of
// `useToast().success(...)`. Just a styling preference.
function useToastApi() {
  return { toast: useToast() };
}
