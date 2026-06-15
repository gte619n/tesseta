"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { ProgramEditModal } from "./ProgramEditModal";

type Props = {
  programId: string;
  programTitle: string;
  programDescription: string;
  status: string;
  activate: () => Promise<void>;
  archive: () => Promise<void>;
  update: (title: string, description: string) => Promise<void>;
};

export function ProgramDetailActions({
  programId,
  programTitle,
  programDescription,
  status,
  activate,
  archive,
  update,
}: Props) {
  const router = useRouter();
  const confirm = useConfirm();
  const toast = useToast();
  const [pending, startTransition] = useTransition();
  const [editing, setEditing] = useState(false);

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
      <button
        type="button"
        onClick={() => setEditing(true)}
        disabled={pending}
        className="caps-mono cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[10px] tracking-[0.06em] text-secondary hover:text-primary disabled:opacity-60"
      >
        Edit
      </button>
      {status === "ACTIVE" ? (
        <button
          type="button"
          onClick={() =>
            router.push(
              `/me/workouts/programs/chat?programId=${encodeURIComponent(programId)}`,
            )
          }
          disabled={pending}
          className="caps-mono cursor-pointer rounded-md border-[0.5px] border-accent/40 bg-accent-bg px-3 py-1.5 text-[10px] tracking-[0.06em] text-accent-dim hover:opacity-90 disabled:opacity-60"
        >
          Refine with AI
        </button>
      ) : null}
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

      <ProgramEditModal
        isOpen={editing}
        initialTitle={programTitle}
        initialDescription={programDescription}
        onClose={() => setEditing(false)}
        onSaved={() => {
          setEditing(false);
          router.refresh();
        }}
        save={update}
      />
    </div>
  );
}
