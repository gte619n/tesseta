import { apiJson } from "./api";
import type {
  GoalResponse,
  GoalDeepResponse,
  GoalStatus,
} from "./types/goals";

// Server-only HTTP helpers for the Goals module. Do not import from
// client components — apiFetch reads server env + the Auth.js session.

export async function listGoals(status?: GoalStatus): Promise<GoalResponse[]> {
  const qs = status ? `?status=${status}` : "";
  return apiJson<GoalResponse[]>(`/api/me/goals${qs}`);
}

export async function getGoalDeep(goalId: string): Promise<GoalDeepResponse> {
  return apiJson<GoalDeepResponse>(`/api/me/goals/${goalId}`);
}
