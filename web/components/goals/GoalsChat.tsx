"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { GoalProposalCard } from "@/components/goals/GoalProposalCard";
import type { GoalProposalDraft } from "@/components/goals/GoalProposalCard";
import { ChatMarkdown } from "@/components/goals/ChatMarkdown";
import { readSseStream } from "@/lib/sse-client";
import {
  draftToProposal,
  proposalToDraft,
  type ChatThread,
  type GoalProposalDto,
} from "@/lib/goals-chat";

// ── Commit action contract ───────────────────────────────────────────
//
// The host page supplies a server action that POSTs the (user-edited)
// proposal to /api/me/goals/chat/{threadId}/commit. On success it returns
// the new goalId; on a 400 the backend re-flags the structure, which we
// surface back onto the card so invalid fields show inline.
export type CommitProposalResult =
  | { ok: true; goalId: string }
  | { ok: false; flagged: GoalProposalDto };

export type CommitProposalAction = (
  threadId: string,
  proposal: GoalProposalDto,
) => Promise<CommitProposalResult>;

export type DeleteThreadAction = (threadId: string) => Promise<void>;

const STARTER_PROMPTS = [
  "Help me build a plan to get my ApoB into optimal range",
  "Plan a 12-week strength base",
  "I want to improve my sleep score — design a roadmap",
];

type ProposalState = {
  draft: GoalProposalDraft;
  // Once committed, the card collapses to a confirmation linking the goal.
  committedGoalId?: string;
  discarded?: boolean;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  text: string;
  proposal?: ProposalState;
  // Marks the live assistant message currently receiving token deltas.
  streaming?: boolean;
};

let msgSeq = 0;
function nextId(): string {
  msgSeq += 1;
  return `m-${msgSeq}-${Math.random().toString(36).slice(2, 7)}`;
}

export function GoalsChat({
  initialThreads,
  commit,
  deleteThread,
}: {
  initialThreads: ChatThread[];
  commit: CommitProposalAction;
  deleteThread: DeleteThreadAction;
}) {
  const toast = useToast();
  const confirm = useConfirm();
  const searchParams = useSearchParams();
  const seededGoalId = searchParams.get("goalId");

  const [threads, setThreads] = useState<ChatThread[]>(initialThreads);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [, startDeleteTransition] = useTransition();

  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  // Keep the threadId fresh inside async stream callbacks without re-binding.
  const threadIdRef = useRef<string | null>(null);
  threadIdRef.current = threadId;

  // Seed the composer with context when arriving from "Edit in chat".
  useEffect(() => {
    if (seededGoalId && messages.length === 0 && input === "") {
      setInput(
        `I'd like to revise my existing goal (id: ${seededGoalId}). Here's what I want to change: `,
      );
    }
    // Only seed once on mount-equivalent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seededGoalId]);

  // Auto-scroll to the latest content as tokens stream in.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const patchMessage = useCallback(
    (id: string, patch: Partial<ChatMessage>) => {
      setMessages((prev) =>
        prev.map((m) => (m.id === id ? { ...m, ...patch } : m)),
      );
    },
    [],
  );

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || streaming) return;

      const userMsg: ChatMessage = {
        id: nextId(),
        role: "user",
        text: trimmed,
      };
      const assistantId = nextId();
      const assistantMsg: ChatMessage = {
        id: assistantId,
        role: "assistant",
        text: "",
        streaming: true,
      };
      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setInput("");
      setStreaming(true);

      const controller = new AbortController();
      abortRef.current = controller;

      let assistantText = "";
      let sawError = false;

      try {
        const res = await fetch("/api/goals/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            threadId: threadIdRef.current,
            message: trimmed,
          }),
          signal: controller.signal,
        });

        if (!res.ok || !res.body) {
          const detail = await res.text().catch(() => "");
          throw new Error(detail || `Request failed (${res.status})`);
        }

        await readSseStream(
          res.body,
          (eventName, data) => {
            if (eventName === "token") {
              try {
                const parsed = JSON.parse(data) as { text?: string };
                if (parsed.text) {
                  assistantText += parsed.text;
                  patchMessage(assistantId, { text: assistantText });
                }
              } catch {
                // Ignore malformed token frames.
              }
            } else if (eventName === "proposal") {
              try {
                const dto = JSON.parse(data) as GoalProposalDto;
                patchMessage(assistantId, {
                  proposal: { draft: proposalToDraft(dto) },
                });
              } catch {
                // Ignore malformed proposal frames.
              }
            } else if (eventName === "error") {
              sawError = true;
              let errText = "The assistant ran into a problem.";
              try {
                const parsed = JSON.parse(data) as { error?: string };
                if (parsed.error) errText = parsed.error;
              } catch {
                /* keep default */
              }
              toast.error("Chat error", { description: errText });
            } else if (eventName === "done") {
              try {
                const parsed = JSON.parse(data) as { threadId?: string };
                if (parsed.threadId) {
                  threadIdRef.current = parsed.threadId;
                  setThreadId(parsed.threadId);
                }
              } catch {
                /* ignore */
              }
            }
          },
          controller.signal,
        );

        // A new thread won't be in the sidebar yet — add a lightweight entry.
        const tid = threadIdRef.current;
        if (tid && !threads.some((t) => t.threadId === tid)) {
          const now = new Date().toISOString();
          setThreads((prev) => [
            {
              threadId: tid,
              title: trimmed.length <= 60 ? trimmed : `${trimmed.slice(0, 57)}...`,
              createdAt: now,
              updatedAt: now,
            },
            ...prev,
          ]);
        }
      } catch (e) {
        if (!(e instanceof DOMException && e.name === "AbortError")) {
          sawError = true;
          toast.error("Couldn't reach the assistant", {
            description: e instanceof Error ? e.message : "Try again.",
          });
        }
      } finally {
        patchMessage(assistantId, {
          streaming: false,
          // If nothing came back at all, leave a gentle placeholder.
          text:
            assistantText ||
            (sawError ? "" : "(no response)"),
        });
        setStreaming(false);
        abortRef.current = null;
      }
    },
    [streaming, threads, patchMessage, toast],
  );

  function startNewThread() {
    abortRef.current?.abort();
    setThreadId(null);
    threadIdRef.current = null;
    setMessages([]);
    setInput("");
    setStreaming(false);
  }

  function switchThread(tid: string) {
    if (tid === threadId) return;
    abortRef.current?.abort();
    setThreadId(tid);
    threadIdRef.current = tid;
    // Backend has no per-thread history read endpoint in v1; switching
    // resets the visible transcript and continues the persisted thread.
    setMessages([]);
    setInput("");
    setStreaming(false);
  }

  async function handleDeleteThread(tid: string) {
    const ok = await confirm({
      title: "Delete this conversation?",
      description: "This can't be undone.",
      confirmLabel: "Delete",
      tone: "danger",
    });
    if (!ok) return;
    startDeleteTransition(async () => {
      try {
        await deleteThread(tid);
        setThreads((prev) => prev.filter((t) => t.threadId !== tid));
        // If the deleted thread is currently open, reset to empty state.
        if (threadIdRef.current === tid) {
          abortRef.current?.abort();
          setThreadId(null);
          threadIdRef.current = null;
          setMessages([]);
          setInput("");
          setStreaming(false);
        }
        toast.success("Conversation deleted");
      } catch {
        toast.error("Couldn't delete conversation", {
          description: "Try again.",
        });
      }
    });
  }

  async function handleCommit(messageId: string, draft: GoalProposalDraft) {
    const tid = threadIdRef.current;
    if (!tid) {
      toast.error("No active thread", {
        description: "Send a message before saving a goal.",
      });
      return;
    }
    const result = await commit(tid, draftToProposal(draft));
    if (result.ok) {
      toast.success("Goal created");
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId && m.proposal
            ? { ...m, proposal: { ...m.proposal, committedGoalId: result.goalId } }
            : m,
        ),
      );
    } else {
      // Re-flag the card with the backend's inline validation errors.
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId && m.proposal
            ? { ...m, proposal: { ...m.proposal, draft: proposalToDraft(result.flagged) } }
            : m,
        ),
      );
      toast.error("Some fields need fixing", {
        description: "The highlighted fields aren't valid yet.",
      });
      // Surface the error to the card's catch so it doesn't show success.
      throw new Error("validation failed");
    }
  }

  function discardProposal(messageId: string) {
    setMessages((prev) =>
      prev.map((m) =>
        m.id === messageId && m.proposal
          ? { ...m, proposal: { ...m.proposal, discarded: true } }
          : m,
      ),
    );
  }

  const isEmpty = messages.length === 0;

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-[220px_1fr]">
      {/* Thread sidebar */}
      <aside className="space-y-2">
        <button
          type="button"
          onClick={startNewThread}
          className="caps-mono w-full cursor-pointer rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
        >
          + New thread
        </button>
        <div className="space-y-1">
          {threads.length === 0 ? (
            <p className="px-1 font-mono text-[10px] leading-[1.5] text-tertiary">
              No threads yet.
            </p>
          ) : (
            threads.map((t) => (
              <div
                key={t.threadId}
                className={`group flex items-center rounded-md ${
                  t.threadId === threadId
                    ? "bg-accent-bg"
                    : "hover:bg-canvas-muted"
                }`}
              >
                <button
                  type="button"
                  onClick={() => switchThread(t.threadId)}
                  className={`min-w-0 flex-1 cursor-pointer truncate px-2.5 py-1.5 text-left text-[12px] ${
                    t.threadId === threadId
                      ? "text-accent-dim"
                      : "text-secondary"
                  }`}
                  title={t.title}
                >
                  {t.title || "Untitled"}
                </button>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    void handleDeleteThread(t.threadId);
                  }}
                  aria-label={`Delete conversation: ${t.title || "Untitled"}`}
                  className="mr-1 flex h-5 w-5 shrink-0 cursor-pointer items-center justify-center rounded opacity-0 text-tertiary hover:bg-canvas hover:text-alert group-hover:opacity-100 focus-visible:opacity-100"
                >
                  <TrashIcon />
                </button>
              </div>
            ))
          )}
        </div>
      </aside>

      {/* Chat surface — height is viewport-bounded so the messages area
          scrolls internally and the Composer stays pinned at the bottom. */}
      <section className="flex h-[calc(100vh-220px)] min-h-[480px] max-h-[820px] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface">
        <div ref={scrollRef} className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-5">
          {isEmpty ? (
            <EmptyState onPick={(p) => void send(p)} disabled={streaming} />
          ) : (
            messages.map((m) => (
              <MessageRow
                key={m.id}
                message={m}
                onCommit={(draft) => handleCommit(m.id, draft)}
                onDiscard={() => discardProposal(m.id)}
              />
            ))
          )}
        </div>

        <Composer
          value={input}
          onChange={setInput}
          onSend={() => void send(input)}
          streaming={streaming}
          placeholder={
            isEmpty
              ? "Describe a goal you want to plan…"
              : "Continue chatting about the goal…"
          }
        />
      </section>
    </div>
  );
}

function MessageRow({
  message,
  onCommit,
  onDiscard,
}: {
  message: ChatMessage;
  onCommit: (draft: GoalProposalDraft) => Promise<void>;
  onDiscard: () => void;
}) {
  if (message.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] whitespace-pre-wrap rounded-[12px] rounded-br-[4px] bg-accent-bg px-3.5 py-2 text-[13px] leading-[1.5] text-accent-dim">
          {message.text}
        </div>
      </div>
    );
  }

  const proposal = message.proposal;
  return (
    <div className="flex flex-col items-start gap-3">
      {message.text ? (
        <div className="max-w-[88%] rounded-[12px] rounded-bl-[4px] bg-canvas px-3.5 py-2.5">
          <ChatMarkdown>{message.text}</ChatMarkdown>
        </div>
      ) : message.streaming ? (
        <div className="rounded-[12px] rounded-bl-[4px] bg-canvas px-3.5 py-2.5">
          <TypingIndicator />
        </div>
      ) : null}

      {message.streaming && message.text ? (
        <div className="pl-1">
          <TypingIndicator />
        </div>
      ) : null}

      {proposal && !proposal.discarded ? (
        proposal.committedGoalId ? (
          <CommittedConfirmation goalId={proposal.committedGoalId} />
        ) : (
          <div className="w-full max-w-[640px]">
            <GoalProposalCard
              initialValue={proposal.draft}
              heading="Proposed goal"
              saveLabel="Save goal"
              onSave={onCommit}
              onDiscard={onDiscard}
            />
          </div>
        )
      ) : null}
    </div>
  );
}

function CommittedConfirmation({ goalId }: { goalId: string }) {
  return (
    <div className="flex w-full max-w-[640px] items-center justify-between rounded-[12px] border-[0.5px] border-accent/40 bg-accent-bg px-4 py-3">
      <div className="flex items-center gap-2">
        <span className="h-1.5 w-1.5 rounded-full bg-accent" aria-hidden />
        <span className="text-[13px] font-medium text-accent-dim">
          Goal created
        </span>
      </div>
      <Link
        href={`/me/goals/${goalId}` as Route}
        className="caps-mono rounded-md border-[0.5px] border-accent/40 bg-surface px-2.5 py-1.5 text-[10px] tracking-[0.06em] text-accent-dim hover:opacity-90"
      >
        View roadmap →
      </Link>
    </div>
  );
}

function EmptyState({
  onPick,
  disabled,
}: {
  onPick: (prompt: string) => void;
  disabled: boolean;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center py-12 text-center">
      <h2 className="m-0 text-[16px] font-medium text-primary">
        Design a goal with the assistant
      </h2>
      <p className="mx-auto mt-2 max-w-[420px] text-[13px] leading-[1.5] text-secondary">
        Describe what you want to achieve. The assistant proposes a roadmap of
        phases and steps that you can edit before saving.
      </p>
      <div className="mt-5 flex w-full max-w-[460px] flex-col gap-2">
        {STARTER_PROMPTS.map((p) => (
          <button
            key={p}
            type="button"
            onClick={() => onPick(p)}
            disabled={disabled}
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3.5 py-2.5 text-left text-[13px] text-secondary hover:border-accent hover:text-primary disabled:opacity-60"
          >
            {p}
          </button>
        ))}
      </div>
    </div>
  );
}

function Composer({
  value,
  onChange,
  onSend,
  streaming,
  placeholder,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  streaming: boolean;
  placeholder: string;
}) {
  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  }
  return (
    <div className="shrink-0 border-t-[0.5px] border-border-subtle p-3">
      <div className="flex items-end gap-2">
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          rows={2}
          placeholder={placeholder}
          className="min-h-[44px] flex-1 resize-none rounded-md border-[0.5px] border-border-default bg-surface px-3 py-2 text-[13px] text-primary outline-none focus:border-accent"
        />
        <button
          type="button"
          onClick={onSend}
          disabled={streaming || !value.trim()}
          className="cursor-pointer rounded-md bg-accent px-4 py-2.5 text-[12px] font-medium text-inverse disabled:opacity-50"
        >
          {streaming ? "…" : "Send"}
        </button>
      </div>
    </div>
  );
}

function TypingIndicator() {
  // Three pulsing dots while the stream is open.
  const dots = useMemo(() => [0, 1, 2], []);
  return (
    <span className="inline-flex items-center gap-1" aria-label="Assistant is typing">
      {dots.map((d) => (
        <span
          key={d}
          className="h-1.5 w-1.5 animate-pulse rounded-full bg-tertiary"
          style={{ animationDelay: `${d * 150}ms` }}
        />
      ))}
    </span>
  );
}

function TrashIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 16 16"
      fill="currentColor"
      className="h-3 w-3"
      aria-hidden
    >
      <path
        fillRule="evenodd"
        d="M5 3.25V4H2.75a.75.75 0 0 0 0 1.5h.3l.815 6.527A1.75 1.75 0 0 0 5.605 13.5h4.79a1.75 1.75 0 0 0 1.74-1.473L13.95 5.5h.3a.75.75 0 0 0 0-1.5H12v-.75A2.25 2.25 0 0 0 9.75 1h-3.5A2.25 2.25 0 0 0 4 3.25V4h1V3.25A1.25 1.25 0 0 1 6.25 2h3.5A1.25 1.25 0 0 1 11 3.25V4H5ZM6.5 7a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-1 0v-4a.5.5 0 0 1 .5-.5Zm3 0a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-1 0v-4a.5.5 0 0 1 .5-.5Z"
        clipRule="evenodd"
      />
    </svg>
  );
}
