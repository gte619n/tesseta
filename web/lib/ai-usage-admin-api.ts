import { apiJson } from './api';

// Admin-only AI usage analytics (IMPL-MULTIUSER-01 P2.3). Server-only.
// Backed by AdminAiUsageController (all reads from monthly rollup docs):
//   GET /api/admin/ai-usage?month=yyyy-MM&trendMonths=   — global summary + trend
//   GET /api/admin/ai-usage/top?month=&limit=            — top spenders
//   GET /api/admin/ai-usage/users/{userId}?month=        — per-user drilldown

export type AiFeatureUsage = {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  images: number;
  costUsd: number;
};

export type AiUsageSummary = {
  scope: string;
  yearMonth: string;
  totalCalls: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalImages: number;
  totalCostUsd: number;
  // Keyed by AiFeature name (e.g. "NUTRITION_DESCRIBE").
  byFeature: Record<string, AiFeatureUsage>;
};

export type MonthlyCostPoint = {
  month: string;
  costUsd: number;
  calls: number;
};

export type GlobalUsageResponse = {
  month: string;
  summary: AiUsageSummary;
  costOverTime: MonthlyCostPoint[];
};

export async function getAiUsageGlobal(
  month?: string,
  trendMonths?: number,
): Promise<GlobalUsageResponse> {
  const sp = new URLSearchParams();
  if (month) sp.set('month', month);
  if (trendMonths) sp.set('trendMonths', String(trendMonths));
  const qs = sp.toString();
  return apiJson<GlobalUsageResponse>(`/api/admin/ai-usage${qs ? `?${qs}` : ''}`);
}

export async function getAiUsageTop(
  month?: string,
  limit?: number,
): Promise<AiUsageSummary[]> {
  const sp = new URLSearchParams();
  if (month) sp.set('month', month);
  if (limit) sp.set('limit', String(limit));
  const qs = sp.toString();
  return apiJson<AiUsageSummary[]>(`/api/admin/ai-usage/top${qs ? `?${qs}` : ''}`);
}

export async function getAiUsageForUser(
  userId: string,
  month?: string,
): Promise<AiUsageSummary> {
  const sp = new URLSearchParams();
  if (month) sp.set('month', month);
  const qs = sp.toString();
  return apiJson<AiUsageSummary>(
    `/api/admin/ai-usage/users/${encodeURIComponent(userId)}${qs ? `?${qs}` : ''}`,
  );
}
