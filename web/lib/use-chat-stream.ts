"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useToast } from "@/components/ui/Toast";
import { consumeChatStream } from "@/lib/chat-stream";

// ── Shared chat-stream state machine ─────────────────────────────────
//
// GoalsChat and WorkoutProgramChat drove byte-identical streaming loops:
// append a user + placeholder assistant message, POST, consume the SSE
// stream (token/proposal/error/done), and reconcile the threadId. The only
// per-feature differences are the request payload and how a `proposal` frame
// parses into draft state — both injected via the `send` config. Everything
// else (message bookkeeping, abort handling, auto-scroll, threadId ref) lives
// here so the two components only carry their own view + domain glue.

export type ChatRole = "user" | "assistant";

export type ChatMessage<P> = {
  id: string;
  role: ChatRole;
  text: string;
  proposal?: P;
  // Marks the live assistant message currently receiving token deltas.
  streaming?: boolean;
};

let msgSeq = 0;
export function nextMessageId(): string {
  msgSeq += 1;
  return `m-${msgSeq}-${Math.random().toString(36).slice(2, 7)}`;
}

export type SendConfig<P> = {
  /** Build the request for this turn (URL + JSON-serializable body). */
  request: () => { url: string; body: unknown };
  /** Parse a raw `proposal` frame into proposal state; return null to ignore. */
  onProposal?: (data: string) => P | null;
  /** Fired after the stream settles with the resolved threadId + sent text. */
  onSettled?: (threadId: string | null, userText: string) => void;
};

export function useChatStream<P>() {
  const toast = useToast();

  const [messages, setMessages] = useState<ChatMessage<P>[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [threadId, setThreadIdState] = useState<string | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  // Keep the threadId fresh inside async stream callbacks without re-binding.
  const threadIdRef = useRef<string | null>(null);
  threadIdRef.current = threadId;

  // Update both the reactive state and the ref so async callbacks that already
  // captured the hook see the new id immediately.
  const setThreadId = useCallback((tid: string | null) => {
    threadIdRef.current = tid;
    setThreadIdState(tid);
  }, []);

  // Auto-scroll to the latest content as tokens stream in.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const patchMessage = useCallback(
    (id: string, patch: Partial<ChatMessage<P>>) => {
      setMessages((prev) =>
        prev.map((m) => (m.id === id ? { ...m, ...patch } : m)),
      );
    },
    [],
  );

  const abort = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
  }, []);

  // Clear the transcript/composer and cancel any in-flight stream. Callers layer
  // their own feature-specific resets (schedule, setup state, …) on top.
  const reset = useCallback(() => {
    abort();
    setMessages([]);
    setInput("");
    setStreaming(false);
    setThreadId(null);
  }, [abort, setThreadId]);

  const send = useCallback(
    async (text: string, config: SendConfig<P>) => {
      const trimmed = text.trim();
      if (!trimmed || streaming) return;

      const userMsg: ChatMessage<P> = {
        id: nextMessageId(),
        role: "user",
        text: trimmed,
      };
      const assistantId = nextMessageId();
      const assistantMsg: ChatMessage<P> = {
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
        const { url, body } = config.request();
        const res = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
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
              const proposal = config.onProposal?.(data);
              if (proposal != null) patchMessage(assistantId, { proposal });
            },
            onError: (message) => {
              sawError = true;
              toast.error("Chat error", { description: message });
            },
            onDone: (tid) => {
              setThreadId(tid);
            },
          },
          controller.signal,
        );

        config.onSettled?.(threadIdRef.current, trimmed);
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
          text: assistantText || (sawError ? "" : "(no response)"),
        });
        setStreaming(false);
        abortRef.current = null;
      }
    },
    [streaming, patchMessage, toast, setThreadId],
  );

  return {
    messages,
    setMessages,
    input,
    setInput,
    streaming,
    setStreaming,
    threadId,
    setThreadId,
    threadIdRef,
    scrollRef,
    abortRef,
    patchMessage,
    abort,
    reset,
    send,
  };
}
