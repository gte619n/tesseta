#!/usr/bin/env node
// Backend endpoint latency probe. Reads N days of Cloud Run request logs via
// ADC and reports per-endpoint p50/p95/p99, warm (cold starts excluded) and
// cold. Used to record the before/after for any backend perf slice.
//
// Usage:
//   TOKEN=$(gcloud auth application-default print-access-token) \
//     node infra/perf/backend-latency.mjs [--since=2026-09-09] [--service=health-fitness-backend]
//
// Requires: ADC with x-goog-user-project=health-fitness-160 (see CLAUDE.md).

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? true];
  }),
);
const PROJECT = args.project || "health-fitness-160";
const SERVICE = args.service || "health-fitness-backend";
const SINCE = args.since || "2026-09-09";
const token = process.env.TOKEN;
if (!token) {
  console.error(
    "Set TOKEN=$(gcloud auth application-default print-access-token). See CLAUDE.md.",
  );
  process.exit(2);
}

const filter =
  `resource.type="cloud_run_revision" ` +
  `resource.labels.service_name="${SERVICE}" ` +
  `logName="projects/${PROJECT}/logs/run.googleapis.com%2Frequests" ` +
  `timestamp>="${SINCE}T00:00:00Z"`;

const body = (pageToken) =>
  JSON.stringify({
    resourceNames: [`projects/${PROJECT}`],
    filter,
    orderBy: "timestamp asc",
    pageSize: 1000,
    pageToken,
  });

let pt;
let pages = 0;
const rows = [];
while (pages < 60) {
  const r = await fetch("https://logging.googleapis.com/v2/entries:list", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "x-goog-user-project": PROJECT,
      "Content-Type": "application/json",
    },
    body: body(pt),
  });
  const j = await r.json();
  if (j.error) {
    console.error(JSON.stringify(j.error));
    process.exit(1);
  }
  for (const e of j.entries || []) {
    const h = e.httpRequest;
    if (!h || !h.latency) continue;
    rows.push({
      m: h.requestMethod,
      u: h.requestUrl,
      l: parseFloat(h.latency),
      inst: e.labels?.instanceId || "?",
    });
  }
  pages++;
  pt = j.nextPageToken;
  if (!pt) break;
}

// First request seen per instance ~= a cold start.
const seen = new Set();
let cold = 0;
for (const r of rows) {
  if (!seen.has(r.inst)) {
    seen.add(r.inst);
    r.cold = true;
    cold++;
  }
}

const norm = (u) => {
  let p = new URL(u).pathname;
  p = p.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, ":id");
  p = p.replace(/wp_[A-Za-z0-9-]+/g, ":pid");
  p = p.replace(/\/\d{4}-\d{2}-\d{2}/g, "/:date").replace(/\/\d+(\/|$)/g, "/:n$1");
  p = p.replace(/\/[A-Za-z0-9_-]{20,}/g, "/:long");
  return p;
};
const pct = (a, q) => {
  const s = [...a].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.floor(q * s.length))];
};

const g = {};
for (const r of rows) {
  if (r.cold) continue;
  const k = `${r.m} ${norm(r.u)}`;
  (g[k] ||= []).push(r.l);
}
const out = Object.entries(g)
  .map(([k, a]) => ({ k, n: a.length, p50: pct(a, 0.5), p95: pct(a, 0.95), p99: pct(a, 0.99) }))
  .sort((a, b) => b.n - a.n);

console.log(`# ${SERVICE} — warm latency (${rows.length} reqs since ${SINCE}, ${cold} cold starts excluded)`);
console.log("n\tp50\tp95\tp99\tendpoint");
for (const o of out.slice(0, 30)) {
  console.log(
    `${o.n}\t${(o.p50 * 1000).toFixed(0)}ms\t${(o.p95 * 1000).toFixed(0)}ms\t${(o.p99 * 1000).toFixed(0)}ms\t${o.k}`,
  );
}
const colds = rows.filter((r) => r.cold).map((r) => r.l);
if (colds.length) {
  console.log(
    `\n# cold-start request latency: n=${colds.length} p50=${(pct(colds, 0.5) * 1000).toFixed(0)}ms p95=${(pct(colds, 0.95) * 1000).toFixed(0)}ms`,
  );
}
