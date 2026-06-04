import { readSseStream } from "@/lib/sse-client";

// Shared consumer for the assistant chat SSE stream used by Goals chat and
// Workout-program chat. The token/error/done framing is identical across both;
// the domain-specific `proposal` payload is handed back raw via onProposal for
// the caller to parse into its own draft shape.

export type ChatStreamHandlers = {
  onToken: (text: string) => void;
  /** Raw JSON payload of a `proposal` frame; the caller parses it. */
  onProposal: (data: string) => void;
  onError: (message: string) => void;
  /** Fired with the persisted threadId from the terminal `done` frame. */
  onDone: (threadId: string) => void;
};

export async function consumeChatStream(
  body: ReadableStream<Uint8Array>,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await readSseStream(
    body,
    (eventName, data) => {
      if (eventName === "token") {
        try {
          const parsed = JSON.parse(data) as { text?: string };
          if (parsed.text) handlers.onToken(parsed.text);
        } catch {
          /* ignore malformed token frames */
        }
      } else if (eventName === "proposal") {
        handlers.onProposal(data);
      } else if (eventName === "error") {
        let message = "The assistant ran into a problem.";
        try {
          const parsed = JSON.parse(data) as { error?: string };
          if (parsed.error) message = parsed.error;
        } catch {
          /* keep default */
        }
        handlers.onError(message);
      } else if (eventName === "done") {
        try {
          const parsed = JSON.parse(data) as { threadId?: string };
          if (parsed.threadId) handlers.onDone(parsed.threadId);
        } catch {
          /* ignore */
        }
      }
    },
    signal,
  );
}
