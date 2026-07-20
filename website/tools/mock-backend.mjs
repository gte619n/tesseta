// Dev-only mock of the Tesseta Spring backend, just enough to render the web
// app's authenticated pages with realistic demo data for marketing screenshots.
// NOT shipped (lives outside website/public). Boot: node website/tools/mock-backend.mjs
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.MOCK_PORT ?? 9099);

let data = {};
try {
  data = JSON.parse(readFileSync(join(__dirname, "demo-data.json"), "utf8"));
} catch {
  data = {};
}

const seenUnmatched = new Set();

function match(method, pathname) {
  // exact
  const exact = data[`${method} ${pathname}`];
  if (exact !== undefined) return exact;
  // path-only (ignore method) fallback for GETs
  const g = data[`GET ${pathname}`];
  if (method === "GET" && g !== undefined) return g;
  return undefined;
}

const server = createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;

  const send = (code, body, type = "application/json") => {
    res.writeHead(code, { "Content-Type": type, "Access-Control-Allow-Origin": "*" });
    res.end(typeof body === "string" ? body : JSON.stringify(body));
  };

  if (req.method === "OPTIONS") return send(204, "");

  // Auth: UAT dev-login → mint a fake session token.
  if (req.method === "POST" && pathname === "/api/auth/dev-login") {
    return send(200, { accessToken: "mock-session-token" });
  }
  if (pathname === "/actuator/health") return send(200, { status: "UP" });

  const found = match(req.method, pathname);
  if (found !== undefined) return send(200, found);

  // Unmatched: log once, return a permissive empty value so pages don't throw.
  const key = `${req.method} ${pathname}${url.search}`;
  if (!seenUnmatched.has(key)) {
    seenUnmatched.add(key);
    console.log("UNMATCHED", key);
  }
  // Heuristic empty: list-ish paths → [], else {}.
  send(200, {});
});

server.listen(PORT, () => console.log(`mock backend on http://localhost:${PORT}`));
