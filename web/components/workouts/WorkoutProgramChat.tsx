"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useTransition,
} from "react";
import Link from "next/link";
import type { Route } from "next";
import { useToast } from "@/components/ui/Toast";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { consumeChatStream } from "@/lib/chat-stream";
import { ChatMarkdown } from "@/components/goals/ChatMarkdown";
import { ChatComposer, TypingIndicator, TrashIcon } from "@/components/chat/ChatPrimitives";
import {
  WorkoutProgramProposalCard,
  type GymOption,
  type LoadExercisesAction,
} from "@/components/workouts/WorkoutProgramProposalCard";
import { TrtPanel } from "@/components/workouts/TrtPanel";
import type { TrtContext } from "@/lib/types/trt";
import {
  proposalToDraft,
  type ProgramProposalDraft,
} from "@/lib/workout-program-chat";
import type {
  WorkoutProgramChatThread,
  WorkoutProgramChatMessage,
  WorkoutProgramChatSchedule,
  WorkoutProgramProposalPayload,
  WeekDay,
} from "@/lib/types/workout-program";
import { WEEK_DAYS, WEEK_DAY_LABEL } from "@/lib/types/workout-program";

// ── Action contracts (server actions supplied by the host page) ───────

export type CommitProgramActionResult =
  | { ok: true; programId: string }
  | { ok: false; issues: string[] };

export type CommitProgramAction = (
  threadId: string,
  draft: ProgramProposalDraft,
  schedule: WorkoutProgramChatSchedule | null,
) => Promise<CommitProgramActionResult>;

export type LoadThreadMessagesAction = (
  threadId: string,
) => Promise<WorkoutProgramChatMessage[]>;

export type DeleteThreadAction = (threadId: string) => Promise<void>;

export type LoadTrtContextAction = () => Promise<TrtContext>;

export type GoalOption = { goalId: string; title: string };

type Props = {
  initialThreads: WorkoutProgramChatThread[];
  gyms: GymOption[];
  goals: GoalOption[];
  commit: CommitProgramAction;
  loadMessages: LoadThreadMessagesAction;
  loadExercises: LoadExercisesAction;
  deleteThread: DeleteThreadAction;
  loadTrtContext: LoadTrtContextAction;
};

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

function parseProposalJson(json: string): WorkoutProgramProposalPayload | null {
  try {
    return JSON.parse(json) as WorkoutProgramProposalPayload;
  } catch {
    return null;
  }
}

function scheduleSummary(s: WorkoutProgramChatSchedule | null): string {
  if (!s || s.trainingDays.length === 0) return "";
  return s.trainingDays.map((d) => WEEK_DAY_LABEL[d]).join(" · ");
}

export function WorkoutProgramChat({
  initialThreads,
  gyms,
  goals,
  commit,
  loadMessages,
  loadExercises,
  deleteThread,
  loadTrtContext,
}: Props) {
  const toast = useToast();
  const confirm = useConfirm();

  const [threads, setThreads] =
    useState<WorkoutProgramChatThread[]>(initialThreads);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [schedule, setSchedule] = useState<WorkoutProgramChatSchedule | null>(
    null,
  );
  const [goalId, setGoalId] = useState<string | null>(null);
  // Whether the user has completed the setup form for the current session.
  const [setupDone, setSetupDone] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [loadingThread, setLoadingThread] = useState(false);
  const [, startDeleteTransition] = useTransition();

  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const threadIdRef = useRef<string | null>(null);
  threadIdRef.current = threadId;
  const scheduleRef = useRef<WorkoutProgramChatSchedule | null>(null);
  scheduleRef.current = schedule;
  const goalIdRef = useRef<string | null>(null);
  goalIdRef.current = goalId;
  // Tracks whether the next /chat POST is the first turn of a new thread.
  const firstTurnRef = useRef(false);

  const gymName = useCallback(
    (id: string | undefined) =>
      id ? gyms.find((g) => g.locationId === id)?.name ?? id : "",
    [gyms],
  );

  const scheduleDisplay = useMemo(() => {
    if (!schedule || schedule.trainingDays.length === 0) return "";
    return schedule.trainingDays
      .map((d) => `${WEEK_DAY_LABEL[d]} @ ${gymName(schedule.dayLocations[d])}`)
      .join(", ");
  }, [schedule, gymName]);

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

      const isFirst = firstTurnRef.current;
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
          body: JSON.stringify(
            isFirst
              ? {
                  message: trimmed,
                  schedule: scheduleRef.current,
                  goalId: goalIdRef.current,
                }
              : { threadId: threadIdRef.current, message: trimmed },
          ),
          signal: controller.signal,
        });

        if (!res.ok || !res.body) {
          const detail = await res.text().catch(() => "");
          throw new Error(detail || `Request failed (${res.status})`);
        }

        await consumeChatStream(
          res.body,
          {
            onToken: (t) => {
              assistantText += t;
              patchMessage(assistantId, { text: assistantText });
            },
            onProposal: (data) => {
              try {
                const parsed = JSON.parse(data) as WorkoutProgramProposalPayload;
                patchMessage(assistantId, {
                  proposal: {
                    draft: proposalToDraft(parsed.program, parsed.issues ?? [], parsed.warnings ?? []),
                  },
                });
              } catch {
                /* ignore malformed proposal */
              }
            },
            onError: (message) => {
              sawError = true;
              toast.error("Chat error", { description: message });
            },
            onDone: (tid) => {
              threadIdRef.current = tid;
              setThreadId(tid);
            },
          },
          controller.signal,
        );

        // After the first turn, subsequent turns continue the persisted thread.
        firstTurnRef.current = false;

        const tid = threadIdRef.current;
        if (tid && !threads.some((t) => t.threadId === tid)) {
          const now = new Date().toISOString();
          setThreads((prev) => [
            {
              threadId: tid,
              title:
                trimmed.length <= 60 ? trimmed : `${trimmed.slice(0, 57)}...`,
              schedule: scheduleRef.current,
              goalId: goalIdRef.current,
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
          text: assistantText || (sawError ? "" : "(no response)"),
        });
        setStreaming(false);
        abortRef.current = null;
      }
    },
    [streaming, threads, patchMessage, toast],
  );

  // Begin a fresh setup form (back to step 1).
  function startNew() {
    abortRef.current?.abort();
    setThreadId(null);
    threadIdRef.current = null;
    setSchedule(null);
    setGoalId(null);
    setSetupDone(false);
    firstTurnRef.current = false;
    setMessages([]);
    setInput("");
    setStreaming(false);
  }

  // Submit the setup form → start the chat with the first message.
  function handleSetupSubmit(
    sched: WorkoutProgramChatSchedule,
    linkedGoalId: string | null,
    firstMessage: string,
  ) {
    setSchedule(sched);
    scheduleRef.current = sched;
    setGoalId(linkedGoalId);
    goalIdRef.current = linkedGoalId;
    setThreadId(null);
    threadIdRef.current = null;
    setMessages([]);
    setSetupDone(true);
    firstTurnRef.current = true;
    void send(firstMessage);
  }

  // Resume a persisted thread: replay messages + re-render the latest proposal.
  function switchThread(t: WorkoutProgramChatThread) {
    if (t.threadId === threadId) return;
    abortRef.current?.abort();
    setStreaming(false);
    setThreadId(t.threadId);
    threadIdRef.current = t.threadId;
    setSchedule(t.schedule);
    scheduleRef.current = t.schedule;
    setGoalId(t.goalId);
    goalIdRef.current = t.goalId;
    setSetupDone(true);
    firstTurnRef.current = false;
    setInput("");
    setMessages([]);
    setLoadingThread(true);

    loadMessages(t.threadId)
      .then((history) => {
        // Find the latest assistant message that carried a proposal so only it
        // renders an editable card (older ones collapse to text).
        let latestProposalIdx = -1;
        history.forEach((h, idx) => {
          if (h.role === "ASSISTANT" && h.proposalJson) latestProposalIdx = idx;
        });
        const mapped: ChatMessage[] = history.map((h, idx) => {
          const role = h.role === "USER" ? "user" : "assistant";
          let proposal: ProposalState | undefined;
          if (idx === latestProposalIdx && h.proposalJson) {
            const payload = parseProposalJson(h.proposalJson);
            if (payload) {
              proposal = {
                draft: proposalToDraft(payload.program, payload.issues ?? [], payload.warnings ?? []),
              };
            }
          }
          return {
            id: nextId(),
            role,
            text: h.content,
            ...(proposal ? { proposal } : {}),
          };
        });
        setMessages(mapped);
      })
      .catch(() => {
        toast.error("Couldn't load conversation", { description: "Try again." });
      })
      .finally(() => setLoadingThread(false));
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
        if (threadIdRef.current === tid) startNew();
        toast.success("Conversation deleted");
      } catch {
        toast.error("Couldn't delete conversation", { description: "Try again." });
      }
    });
  }

  async function handleCommit(messageId: string, draft: ProgramProposalDraft) {
    const tid = threadIdRef.current;
    if (!tid) {
      toast.error("No active thread", {
        description: "Send a message before saving a program.",
      });
      return;
    }
    const result = await commit(tid, draft, scheduleRef.current);
    if (result.ok) {
      toast.success("Program created");
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId && m.proposal
            ? {
                ...m,
                proposal: {
                  ...m.proposal,
                  committedProgramId: result.programId,
                },
              }
            : m,
        ),
      );
    } else {
      // Re-flag the card with the backend's issues (keep editing).
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

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-[220px_1fr]">
      {/* Thread sidebar */}
      <aside className="space-y-2">
        <button
          type="button"
          onClick={startNew}
          className="caps-mono w-full cursor-pointer rounded-md bg-accent px-3 py-2 text-[10px] tracking-[0.06em] text-inverse hover:opacity-90"
        >
          + New program
        </button>
        <div className="space-y-1">
          {threads.length === 0 ? (
            <p className="px-1 font-mono text-[10px] leading-[1.5] text-tertiary">
              No conversations yet.
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
                  onClick={() => switchThread(t)}
                  className={`min-w-0 flex-1 cursor-pointer px-2.5 py-1.5 text-left ${
                    t.threadId === threadId ? "text-accent-dim" : "text-secondary"
                  }`}
                  title={t.title}
                >
                  <span className="block truncate text-[12px]">
                    {t.title || "Untitled"}
                  </span>
                  {t.schedule ? (
                    <span className="caps-mono block truncate text-[8px] tracking-[0.06em] text-tertiary">
                      {scheduleSummary(t.schedule)}
                    </span>
                  ) : null}
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

      {/* Main surface: setup form (step 1) or chat (step 2). */}
      {!setupDone ? (
        <SetupForm gyms={gyms} goals={goals} onSubmit={handleSetupSubmit} />
      ) : (
        <div className="space-y-4">
        {/* TRT / monitoring panel + danger banner (IMPL-18 / ADR-0015). Only
            renders when the user is on TRT or has relevant labs. */}
        <TrtPanel loadTrtContext={loadTrtContext} />
        <section className="flex h-[calc(100vh-240px)] min-h-[480px] max-h-[860px] flex-col overflow-hidden rounded-[14px] border-[0.5px] border-border-default bg-surface">
          {scheduleDisplay ? (
            <div className="shrink-0 border-b-[0.5px] border-border-subtle px-5 py-2">
              <span className="caps-mono text-[9px] tracking-[0.06em] text-tertiary">
                Schedule:{" "}
              </span>
              <span className="text-[12px] text-secondary">{scheduleDisplay}</span>
            </div>
          ) : null}
          <div
            ref={scrollRef}
            className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-5"
          >
            {loadingThread ? (
              <p className="font-mono text-[11px] text-tertiary">
                Loading conversation…
              </p>
            ) : messages.length === 0 ? (
              <p className="font-mono text-[11px] text-tertiary">
                Starting the conversation…
              </p>
            ) : (
              messages.map((m) => (
                <MessageRow
                  key={m.id}
                  message={m}
                  schedule={schedule}
                  gyms={gyms}
                  loadExercises={loadExercises}
                  onCommit={(draft) => handleCommit(m.id, draft)}
                  onDiscard={() => discardProposal(m.id)}
                />
              ))
            )}
          </div>

          <ChatComposer
            value={input}
            onChange={setInput}
            onSend={() => void send(input)}
            streaming={streaming}
            placeholder="Refine the program…"
          />
        </section>
        </div>
      )}
    </div>
  );
}

// ── Setup form (step 1) ──────────────────────────────────────────────

function SetupForm({
  gyms,
  goals,
  onSubmit,
}: {
  gyms: GymOption[];
  goals: GoalOption[];
  onSubmit: (
    schedule: WorkoutProgramChatSchedule,
    goalId: string | null,
    firstMessage: string,
  ) => void;
}) {
  const toast = useToast();
  const [days, setDays] = useState<WeekDay[]>([]);
  const [dayLocations, setDayLocations] = useState<
    Partial<Record<WeekDay, string>>
  >({});
  const [goalId, setGoalId] = useState<string>("");
  const [message, setMessage] = useState("");

  const defaultGym = gyms[0]?.locationId ?? "";

  function toggleDay(d: WeekDay) {
    setDays((prev) => {
      if (prev.includes(d)) {
        setDayLocations((locs) => {
          const next = { ...locs };
          delete next[d];
          return next;
        });
        return prev.filter((x) => x !== d);
      }
      setDayLocations((locs) => ({ ...locs, [d]: locs[d] ?? defaultGym }));
      return [...prev, d];
    });
  }

  function handleSubmit() {
    if (gyms.length === 0) {
      toast.error("No gyms found", {
        description: "Add a gym before designing a program.",
      });
      return;
    }
    if (days.length === 0) {
      toast.error("Pick training days", {
        description: "Select at least one day you'll train.",
      });
      return;
    }
    const missing = days.filter((d) => !dayLocations[d]);
    if (missing.length > 0) {
      toast.error("Pick a gym for each day", {
        description: `Missing: ${missing.map((d) => WEEK_DAY_LABEL[d]).join(", ")}`,
      });
      return;
    }
    if (!message.trim()) {
      toast.error("Describe what you're training for", {
        description: "Add a goal so the assistant can start.",
      });
      return;
    }
    // Keep training days in canonical week order.
    const ordered = WEEK_DAYS.filter((d) => days.includes(d));
    onSubmit(
      { trainingDays: ordered, dayLocations },
      goalId || null,
      message.trim(),
    );
  }

  const orderedSelected = WEEK_DAYS.filter((d) => days.includes(d));

  return (
    <section className="rounded-[14px] border-[0.5px] border-border-default bg-surface">
      <div className="border-b-[0.5px] border-border-subtle px-5 py-3">
        <h2 className="m-0 text-[14px] font-medium text-primary">
          Set up your program
        </h2>
        <p className="mt-0.5 text-[12px] text-secondary">
          Choose your training days and gyms. These are fixed for the
          conversation — every prescribed exercise must be executable at that
          day&apos;s gym.
        </p>
      </div>

      <div className="space-y-5 px-5 py-5">
        <div>
          <span className="caps-mono mb-2 block text-[9px] tracking-[0.06em] text-tertiary">
            Training days
          </span>
          <div className="flex flex-wrap gap-1.5">
            {WEEK_DAYS.map((d) => (
              <button
                key={d}
                type="button"
                onClick={() => toggleDay(d)}
                className={`caps-mono cursor-pointer rounded-md border-[0.5px] px-3 py-1.5 text-[10px] tracking-[0.06em] ${
                  days.includes(d)
                    ? "border-accent bg-accent-bg text-accent-dim"
                    : "border-border-default bg-canvas text-secondary hover:border-accent"
                }`}
              >
                {WEEK_DAY_LABEL[d]}
              </button>
            ))}
          </div>
        </div>

        {orderedSelected.length > 0 ? (
          <div>
            <span className="caps-mono mb-2 block text-[9px] tracking-[0.06em] text-tertiary">
              Gym per day
            </span>
            <div className="space-y-1.5">
              {orderedSelected.map((d) => (
                <div key={d} className="flex items-center gap-3">
                  <span className="caps-mono w-10 text-[10px] tracking-[0.06em] text-secondary">
                    {WEEK_DAY_LABEL[d]}
                  </span>
                  <select
                    value={dayLocations[d] ?? ""}
                    onChange={(e) =>
                      setDayLocations((locs) => ({ ...locs, [d]: e.target.value }))
                    }
                    className="flex-1 cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent"
                  >
                    {gyms.length === 0 ? <option value="">No gyms</option> : null}
                    {gyms.map((g) => (
                      <option key={g.locationId} value={g.locationId}>
                        {g.name}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        <label className="block">
          <span className="caps-mono mb-1.5 block text-[9px] tracking-[0.06em] text-tertiary">
            Link a goal (optional)
          </span>
          <select
            value={goalId}
            onChange={(e) => setGoalId(e.target.value)}
            className="w-full cursor-pointer rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-1.5 text-[13px] text-primary outline-none focus:border-accent"
          >
            <option value="">No linked goal</option>
            {goals.map((g) => (
              <option key={g.goalId} value={g.goalId}>
                {g.title}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="caps-mono mb-1.5 block text-[9px] tracking-[0.06em] text-tertiary">
            What are you training for?
          </span>
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={3}
            placeholder="e.g. 12-week strength base, deload every 4th week"
            className="w-full resize-none rounded-md border-[0.5px] border-border-default bg-surface px-2.5 py-2 text-[13px] text-primary outline-none focus:border-accent"
          />
        </label>
      </div>

      <div className="flex items-center justify-end border-t-[0.5px] border-border-subtle px-5 py-3">
        <button
          type="button"
          onClick={handleSubmit}
          className="cursor-pointer rounded-md bg-accent px-4 py-2 text-[12px] font-medium text-inverse hover:opacity-90"
        >
          Start designing →
        </button>
      </div>
    </section>
  );
}

// ── Message row ──────────────────────────────────────────────────────

function MessageRow({
  message,
  schedule,
  gyms,
  loadExercises,
  onCommit,
  onDiscard,
}: {
  message: ChatMessage;
  schedule: WorkoutProgramChatSchedule | null;
  gyms: GymOption[];
  loadExercises: LoadExercisesAction;
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
              schedule={schedule}
              gyms={gyms}
              loadExercises={loadExercises}
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
        <span className="text-[13px] font-medium text-accent-dim">
          Program created
        </span>
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

