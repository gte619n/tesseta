"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import { readSseStream } from "@/lib/sse-client";
import { ChatMarkdown } from "@/components/goals/ChatMarkdown";
import {
  WorkoutProgramProposalCard,
  type ProgramProposalDraft,
} from "@/components/workouts/WorkoutProgramProposalCard";
import type { WorkoutProgramDeepResponse } from "@/lib/types/workout-program";
import type { ChatHistoryEntry } from "@/lib/workout-program-chat";

// ── Commit action contract ───────────────────────────────────────────
//
// The host page supplies a server action that POSTs the (edited) proposal to
// /api/me/workout-programs/chat/commit. On success it returns the new
// programId; on a 422 the backend returns inline issues which we re-surface.
export type CommitProgramActionResult =
  | { ok: true; programId: string }
  | { ok: false; issues: string[] };

export type CommitProgramAction = (
  draft: ProgramProposalDraft,
) => Promise<CommitProgramActionResult>;

const STARTER_PROMPTS = [
  "Build me a 4-day upper/lower for hypertrophy, deload every 4th week",
  "I train Mon/Wed/Fri at my home gym and Saturdays at the office gym — design a strength block",
  "12-week strength base, 3 days/week, with a deload every 4th week",
];

type ProposalState = {
  draft: ProgramProposalDraft;
  committedProgramId?: string;
  discarded?: boolean;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  text: string;
  proposal?: ProposalState;
  streaming?: boolean;
};

let msgSeq = 0;
function nextId(): string {
  msgSeq += 1;
  return `m-${msgSeq}-${Math.random().toString(36).slice(2, 7)}`;
}

export function WorkoutProgramChat({ commit }: { commit: CommitProgramAction }) {
  const toast = useToast();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const patchMessage = useCallback((id: string, patch: Partial<ChatMessage>) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  }, []);

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || streaming) return;

      // Build history from the visible transcript (user + assistant turns) so
      // the backend has conversational context — the chat endpoint is stateless.
      const history: ChatHistoryEntry[] = messages
        .filter((m) => m.text.trim().length > 0)
        .map((m) => ({ role: m.role, content: m.text }));

      const userMsg: ChatMessage = { id: nextId(), role: "user", text: trimmed };
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
        const res = await fetch("/api/workout-programs/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ message: trimmed, history }),
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
                /* ignore malformed token */
              }
            } else if (eventName === "proposal") {
              try {
                const parsed = JSON.parse(data) as {
                  program: WorkoutProgramDeepResponse;
                  issues?: string[];
                };
                patchMessage(assistantId, {
                  proposal: {
                    draft: {
                      title: parsed.program.title,
                      description: parsed.program.description,
                      proposal: parsed.program,
                      issues: parsed.issues ?? [],
                    },
                  },
                });
              } catch {
                /* ignore malformed proposal */
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
            }
            // `done` carries no payload we need here.
          },
          controller.signal,
        );
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
          text: assistantText || (sawError ? "" : "(no response)"),
        });
        setStreaming(false);
        abortRef.current = null;
      }
    },
    [streaming, messages, patchMessage, toast],
  );

  function startNew() {
    abortRef.current?.abort();
    setMessages([]);
    setInput("");
    setStreaming(false);
  }

  async function handleCommit(messageId: string, draft: ProgramProposalDraft) {
    const result = await commit(draft);
    if (result.ok) {
      toast.success("Program created");
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId && m.proposal
            ? { ...m, proposal: { ...m.proposal, committedProgramId: result.programId } }
            : m,
        ),
      );
    } else {
      // Re-flag the card with the backend's issues.
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId && m.proposal
            ? {
                ...m,
                proposal: {
                  ...m.proposal,
                  draft: { ...m.proposal.draft, issues: result.issues },
                },
              }
            : m,
        ),
      );
      toast.error("Some issues need fixing", {
        description: "The program wasn't saved — resolve the flagged issues.",
      });
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
    <div className="space-y-3">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={startNew}
          className="caps-mono cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-1.5 text-[10px] tracking-[0.06em] text-secondary hover:text-primary"
        >
          + New program
        </button>
      </div>

      <section className="flex h-[calc(100vh-240px)] min-h-[480px] max-h-[860px] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface">
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
            isEmpty ? "Describe the program you want to build…" : "Refine the program…"
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
  onCommit: (draft: ProgramProposalDraft) => Promise<void>;
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
        proposal.committedProgramId ? (
          <CommittedConfirmation programId={proposal.committedProgramId} />
        ) : (
          <div className="w-full max-w-[680px]">
            <WorkoutProgramProposalCard
              initialValue={proposal.draft}
              onSave={onCommit}
              onDiscard={onDiscard}
            />
          </div>
        )
      ) : null}
    </div>
  );
}

function CommittedConfirmation({ programId }: { programId: string }) {
  return (
    <div className="flex w-full max-w-[680px] items-center justify-between rounded-[12px] border-[0.5px] border-accent/40 bg-accent-bg px-4 py-3">
      <div className="flex items-center gap-2">
        <span className="h-1.5 w-1.5 rounded-full bg-accent" aria-hidden />
        <span className="text-[13px] font-medium text-accent-dim">Program created</span>
      </div>
      <Link
        href={`/me/workouts/programs/${programId}` as Route}
        className="caps-mono rounded-md border-[0.5px] border-accent/40 bg-surface px-2.5 py-1.5 text-[10px] tracking-[0.06em] text-accent-dim hover:opacity-90"
      >
        View program →
      </Link>
    </div>
  );
}

function EmptyState({ onPick, disabled }: { onPick: (p: string) => void; disabled: boolean }) {
  return (
    <div className="flex h-full flex-col items-center justify-center py-12 text-center">
      <h2 className="m-0 text-[16px] font-medium text-primary">Design a program with the assistant</h2>
      <p className="mx-auto mt-2 max-w-[460px] text-[13px] leading-[1.5] text-secondary">
        Describe what you&apos;re training for and which days/gyms you train at. The assistant
        proposes an editable periodized program — every exercise executable at that day&apos;s gym.
      </p>
      <div className="mt-5 flex w-full max-w-[520px] flex-col gap-2">
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
