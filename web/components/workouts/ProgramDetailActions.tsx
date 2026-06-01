"use client";

import { useTransition } from "react";
import { useRouter } from "next/navigation";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

type Props = {
  programTitle: string;
  status: string;
  activate: () => Promise<void>;
  archive: () => Promise<void>;
};

export function ProgramDetailActions({ programTitle, status, activate, archive }: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [pending, startTransition] = useTransition();

  function onActivate() {
    startTransition(async () => {
      try {
        await activate();
        toast.success("Program activated", {
          description: "Scheduled sessions have been materialized.",
        });
        router.refresh();
      } catch {
        toast.error("Couldn't activate program", { description: "Try again." });
      }
    });
  }

  async function onArchive() {
    const ok = await confirm({
      title: "Archive program?",
      description: `Archive "${programTitle}"? It will be hidden from your active list.`,
      confirmLabel: "Archive",
      tone: "danger",
    });
    if (!ok) return;
    startTransition(async () => {
      try {
        await archive();
        toast.success("Program archived");
        router.push("/me/workouts/programs");
      } catch {
        toast.error("Couldn't archive program", { description: "Try again." });
      }
    });
  }

  return (
    <div className="flex items-center gap-2">
      {status === "DRAFT" || status === "ACTIVE" ? (
        <button
          type="button"
          onClick={onActivate}
          disabled={pending}
          className="caps-mono cursor-pointer rounded-md bg-accent px-3 py-1.5 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90 disabled:opacity-60"
        >
          {status === "ACTIVE" ? "Re-activate" : "Activate"}
        </button>
      ) : null}
      {status !== "ARCHIVED" ? (
        <button
          type="button"
          onClick={onArchive}
          disabled={pending}
          className="caps-mono cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[10px] tracking-[0.06em] text-secondary hover:text-primary disabled:opacity-60"
        >
          Archive
        </button>
      ) : null}
    </div>
  );
}
