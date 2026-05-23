"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { useConfirm } from "@/components/ui/ConfirmDialog";

type Props = {
  reportId: string;
  deleteReport: (reportId: string) => Promise<void>;
};

export function BloodReportActions({ reportId, deleteReport }: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const [isPending, startTransition] = useTransition();

  async function handleDelete() {
    const ok = await confirm({
      title: "Delete this report?",
      description: "This will permanently remove the report and its extracted markers.",
      confirmLabel: "Delete",
      tone: "danger",
    });
    if (!ok) return;

    startTransition(async () => {
      await deleteReport(reportId);
      router.refresh();
    });
  }

  function handleViewPdf() {
    window.open(`/api/blood/reports/${reportId}/pdf`, "_blank");
  }

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={handleViewPdf}
        className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1 font-mono text-[10px] uppercase tracking-[0.04em] text-secondary hover:border-border-strong hover:text-primary"
      >
        PDF
      </button>
      <button
        type="button"
        onClick={handleDelete}
        disabled={isPending}
        className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-2 py-1 font-mono text-[10px] uppercase tracking-[0.04em] text-secondary hover:border-red-500 hover:text-red-600 disabled:opacity-50"
      >
        {isPending ? "..." : "Delete"}
      </button>
    </div>
  );
}
