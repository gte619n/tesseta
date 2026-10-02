import { getAiUsageGlobal, getAiUsageTop } from '@/lib/ai-usage-admin-api';
import type { AiUsageSummary } from '@/lib/ai-usage-admin-api';
import { pageMetadata } from '@/lib/page-metadata';

export const metadata = pageMetadata('AI usage');

// Read-only internal-ops dashboard (D4). Rendered per request; all data comes
// from monthly rollup docs (no event scans).
export const dynamic = 'force-dynamic';

const usd = (n: number) =>
  `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const num = (n: number) => n.toLocaleString();

function featureLabel(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export default async function AdminAiUsagePage() {
  // Admin gating handled by app/admin/layout.tsx (and again in apiFetch).
  // Month defaults to the current UTC month on the backend.
  const [global, top] = await Promise.all([
    getAiUsageGlobal(),
    getAiUsageTop(undefined, 20).catch(() => [] as AiUsageSummary[]),
  ]);

  const { summary, costOverTime, month } = global;
  const features = Object.entries(summary.byFeature).sort(
    (a, b) => b[1].costUsd - a[1].costUsd,
  );

  return (
    <div className="container mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-primary">AI usage</h1>
          <p className="mt-0.5 text-sm text-tertiary">
            Internal-ops metering — estimated cost from the config price table.
          </p>
        </div>
        <span className="text-sm text-secondary">{month}</span>
      </div>

      {/* This-month totals */}
      <section className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Total cost" value={usd(summary.totalCostUsd)} />
        <Stat label="Calls" value={num(summary.totalCalls)} />
        <Stat
          label="Tokens (in / out)"
          value={`${num(summary.totalInputTokens)} / ${num(summary.totalOutputTokens)}`}
        />
        <Stat label="Images" value={num(summary.totalImages)} />
      </section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Per-feature breakdown */}
        <section className="rounded-lg border border-border-default bg-surface p-5">
          <h2 className="mb-4 text-base font-semibold text-primary">
            By feature
          </h2>
          {features.length === 0 ? (
            <p className="text-sm text-tertiary">No usage this month.</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-tertiary">
                  <th className="pb-2 font-medium">Feature</th>
                  <th className="pb-2 text-right font-medium">Calls</th>
                  <th className="pb-2 text-right font-medium">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-default">
                {features.map(([key, f]) => (
                  <tr key={key}>
                    <td className="py-2 text-primary">{featureLabel(key)}</td>
                    <td className="py-2 text-right text-secondary">
                      {num(f.calls)}
                    </td>
                    <td className="py-2 text-right text-primary">
                      {usd(f.costUsd)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {/* Top spenders */}
        <section className="rounded-lg border border-border-default bg-surface p-5">
          <h2 className="mb-4 text-base font-semibold text-primary">
            Top spenders
          </h2>
          {top.length === 0 ? (
            <p className="text-sm text-tertiary">No usage this month.</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-tertiary">
                  <th className="pb-2 font-medium">User</th>
                  <th className="pb-2 text-right font-medium">Calls</th>
                  <th className="pb-2 text-right font-medium">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-default">
                {top.map((u) => (
                  <tr key={u.scope}>
                    <td className="py-2 font-mono text-xs text-primary">
                      {u.scope}
                    </td>
                    <td className="py-2 text-right text-secondary">
                      {num(u.totalCalls)}
                    </td>
                    <td className="py-2 text-right text-primary">
                      {usd(u.totalCostUsd)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>

      {/* Cost over time */}
      {costOverTime.length > 0 && (
        <section className="mt-6 rounded-lg border border-border-default bg-surface p-5">
          <h2 className="mb-4 text-base font-semibold text-primary">
            Cost over time
          </h2>
          <ul className="divide-y divide-border-default">
            {costOverTime.map((p) => (
              <li
                key={p.month}
                className="flex items-center justify-between py-2 text-sm"
              >
                <span className="text-secondary">{p.month}</span>
                <span className="flex items-center gap-4">
                  <span className="text-tertiary">{num(p.calls)} calls</span>
                  <span className="w-20 text-right text-primary">
                    {usd(p.costUsd)}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border-default bg-surface p-4">
      <p className="text-xs text-tertiary">{label}</p>
      <p className="mt-1 text-lg font-semibold text-primary">{value}</p>
    </div>
  );
}
