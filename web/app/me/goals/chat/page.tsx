import { Suspense } from "react";
import Link from "next/link";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import { listChatThreads, commitChatProposal, deleteChatThread } from "@/lib/goals-api";
import type { ChatThread, GoalProposalDto } from "@/lib/types/goals-chat-wire";
import { GoalsChat } from "@/components/goals/GoalsChat";
import type { CommitProposalResult } from "@/components/goals/GoalsChat";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Goal Coach");

export const dynamic = "force-dynamic";

export default async function GoalsChatPage() {
  // Prefetch the thread list server-side (backend URL + bearer are
  // server-only). The SSE send itself goes through app/api/goals/chat.
  let threads: ChatThread[];
  try {
    threads = await listChatThreads();
  } catch {
    threads = [];
  }

  // Commit the (user-edited) proposal. Reuses the same persistence path
  // the manual editor hits, via the chat commit endpoint. A 400 returns
  // the re-flagged structure so the card can show inline errors.
  async function commit(
    threadId: string,
    proposal: GoalProposalDto,
  ): Promise<CommitProposalResult> {
    "use server";
    const result = await commitChatProposal(threadId, proposal);
    if (result.ok) {
      revalidatePath("/me/goals");
      revalidatePath(`/me/goals/${result.goalId}`);
      return { ok: true, goalId: result.goalId };
    }
    return { ok: false, flagged: result.flagged };
  }

  async function deleteThread(threadId: string): Promise<void> {
    "use server";
    await deleteChatThread(threadId);
    revalidatePath("/me/goals/chat");
  }

  return (
    <main className="min-h-screen bg-canvas p-8">
      <div className="mx-auto max-w-[920px] space-y-6">
        <Link
          href={"/me/goals" as Route}
          className="inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.04em] text-tertiary hover:text-secondary"
        >
          ← Goals
        </Link>

        <header>
          <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
            Goal assistant
          </h1>
          <p className="mt-1 text-[13px] text-secondary">
            Describe an objective and the assistant proposes an editable
            roadmap of phases and steps. Review, tweak, and save it as a goal.
          </p>
        </header>

        <Suspense fallback={null}>
          <GoalsChat initialThreads={threads} commit={commit} deleteThread={deleteThread} />
        </Suspense>
      </div>
    </main>
  );
}
