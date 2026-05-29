import { apiFetch, apiJson, BackendError } from "./api";
import type {
  GoalResponse,
  GoalDeepResponse,
  GoalStatus,
  GoalDomain,
  GoalSource,
  PhaseResponse,
  StepResponse,
  StepKind,
  Comparator,
} from "./types/goals";
import type { ChatThread, GoalProposalDto } from "./types/goals-chat-wire";

// Server-only HTTP helpers for the Goals module. Do not import from
// client components — apiFetch reads server env + the Auth.js session.
// Keep these thin: mutation orchestration (create-goal-then-phases-then-
// steps) lives in server actions, not here.

// ── Reads ────────────────────────────────────────────────────────────

export async function listGoals(status?: GoalStatus): Promise<GoalResponse[]> {
  const qs = status ? `?status=${status}` : "";
  return apiJson<GoalResponse[]>(`/api/me/goals${qs}`);
}

export async function getGoalDeep(goalId: string): Promise<GoalDeepResponse> {
  return apiJson<GoalDeepResponse>(`/api/me/goals/${goalId}`);
}

// ── Request body shapes (mirror backend dto records) ─────────────────

export type MetricBindingInput = {
  metricKey: string;
  comparator: Comparator;
  targetValue: number;
  windowDays?: number | null;
  countFrom?: string | null;
};

export type CreateGoalInput = {
  title: string;
  description: string;
  domain: GoalDomain;
  startDate: string;
  targetDate: string;
  source: GoalSource;
};

export type UpdateGoalInput = Partial<{
  title: string;
  description: string;
  domain: GoalDomain;
  status: GoalStatus;
  startDate: string;
  targetDate: string;
}>;

export type CreatePhaseInput = {
  title: string;
  description: string;
  targetStartDate: string;
  targetEndDate: string;
};

export type UpdatePhaseInput = Partial<CreatePhaseInput>;

export type CreateStepInput = {
  title: string;
  kind: StepKind;
  metric?: MetricBindingInput | null;
};

export type UpdateStepInput = Partial<{
  title: string;
  kind: StepKind;
  done: boolean;
  metric: MetricBindingInput | null;
  resetToAuto: boolean;
}>;

// ── Internal request helper ──────────────────────────────────────────

async function send<T>(
  path: string,
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  body?: unknown,
): Promise<T> {
  const res = await apiFetch(path, {
    method,
    ...(body !== undefined
      ? {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    throw new BackendError(`${method} ${path} returned ${res.status}`, res.status);
  }
  // 204 / empty body responses (DELETE, reorder) have nothing to parse.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

// ── Goal mutations ───────────────────────────────────────────────────

export function createGoal(input: CreateGoalInput): Promise<GoalResponse> {
  return send<GoalResponse>("/api/me/goals", "POST", input);
}

export function updateGoal(
  goalId: string,
  input: UpdateGoalInput,
): Promise<GoalResponse> {
  return send<GoalResponse>(`/api/me/goals/${goalId}`, "PATCH", input);
}

export function archiveGoal(goalId: string): Promise<void> {
  return send<void>(`/api/me/goals/${goalId}`, "DELETE");
}

// Backend POST .../reevaluate returns 204 No Content — nothing to parse.
export function reevaluateGoal(goalId: string): Promise<void> {
  return send<void>(`/api/me/goals/${goalId}/reevaluate`, "POST");
}

// ── Phase mutations ──────────────────────────────────────────────────

export function createPhase(
  goalId: string,
  input: CreatePhaseInput,
): Promise<PhaseResponse> {
  return send<PhaseResponse>(`/api/me/goals/${goalId}/phases`, "POST", input);
}

export function updatePhase(
  goalId: string,
  phaseId: string,
  input: UpdatePhaseInput,
): Promise<PhaseResponse> {
  return send<PhaseResponse>(
    `/api/me/goals/${goalId}/phases/${phaseId}`,
    "PATCH",
    input,
  );
}

export function deletePhase(goalId: string, phaseId: string): Promise<void> {
  return send<void>(`/api/me/goals/${goalId}/phases/${phaseId}`, "DELETE");
}

export function reorderPhases(goalId: string, ids: string[]): Promise<void> {
  return send<void>(`/api/me/goals/${goalId}/phases/order`, "PUT", { ids });
}

// ── Step mutations ───────────────────────────────────────────────────

export function createStep(
  goalId: string,
  phaseId: string,
  input: CreateStepInput,
): Promise<StepResponse> {
  return send<StepResponse>(
    `/api/me/goals/${goalId}/phases/${phaseId}/steps`,
    "POST",
    input,
  );
}

export function updateStep(
  goalId: string,
  phaseId: string,
  stepId: string,
  input: UpdateStepInput,
): Promise<StepResponse> {
  return send<StepResponse>(
    `/api/me/goals/${goalId}/phases/${phaseId}/steps/${stepId}`,
    "PATCH",
    input,
  );
}

export function deleteStep(
  goalId: string,
  phaseId: string,
  stepId: string,
): Promise<void> {
  return send<void>(
    `/api/me/goals/${goalId}/phases/${phaseId}/steps/${stepId}`,
    "DELETE",
  );
}

export function reorderSteps(
  goalId: string,
  phaseId: string,
  ids: string[],
): Promise<void> {
  return send<void>(
    `/api/me/goals/${goalId}/phases/${phaseId}/steps/order`,
    "PUT",
    { ids },
  );
}

// ── Chat ─────────────────────────────────────────────────────────────
//
// The SSE chat send goes through the route handler in app/api/goals/chat
// (the browser needs to read the stream). Thread listing and commit are
// plain JSON, so they live here as server-only helpers.

export function listChatThreads(): Promise<ChatThread[]> {
  return apiJson<ChatThread[]>("/api/me/goals/chat/threads");
}

export type CommitChatResult =
  | { ok: true; goalId: string }
  | { ok: false; flagged: GoalProposalDto };

// Commit a (user-edited) proposal. The backend returns { goalId } on
// success, or 400 with the re-flagged GoalProposalDto when validation
// fails — we distinguish the two so the UI can re-render inline errors.
export async function commitChatProposal(
  threadId: string,
  proposal: GoalProposalDto,
): Promise<CommitChatResult> {
  const res = await apiFetch(`/api/me/goals/chat/${threadId}/commit`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(proposal),
  });
  if (res.ok) {
    const { goalId } = (await res.json()) as { goalId: string };
    return { ok: true, goalId };
  }
  if (res.status === 400) {
    const flagged = (await res.json()) as GoalProposalDto;
    return { ok: false, flagged };
  }
  throw new BackendError(
    `commit returned ${res.status}`,
    res.status,
  );
}
