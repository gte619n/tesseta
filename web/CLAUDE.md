# CLAUDE.md — web

- **App Router only**, no Pages Router.
- **Server Components by default.** Add `"use client"` only when the file
  needs state, browser APIs, or event handlers.
- Streaming + Suspense are first-class. Don't synthesize loading states the
  framework already provides.
- **Data fetching**: server-side `fetch` inside Server Components.
  Use **SSE** (`text/event-stream`) for LLM streaming, not WebSockets.
- **Styling**: Tailwind v4 utility classes only. No CSS modules, no
  `styled-components`.
- **No client-side state managers** (Redux, Zustand, MobX) until there's a
  real cross-component shared-state case. Server Components + URL state +
  React state cover most cases.
- **Components**: `components/ui/` follows shadcn/ui patterns — copy-in,
  owned in this repo, not imported from a dependency.
- TS strict + `noUncheckedIndexedAccess`. Treat type errors as build failures.

## Backend calls from client components — server-action-as-prop
- `lib/*-api.ts` (e.g., `gym-api.ts`) are **server-only HTTP helpers** —
  they import `apiFetch` from `lib/api.ts`, which reads server env
  (`process.env.BACKEND_URL`) and the Auth.js session via `auth()`. They
  MUST NOT be imported from `"use client"` components — at runtime
  `BACKEND_URL` is undefined in the browser bundle and `apiFetch` throws
  `BACKEND_URL is not configured`.
- **Pattern:** Server pages (Server Components in `app/`) define the
  mutation/query inline as a server action and pass it down to client
  components as a callback prop.

  ```tsx
  // app/me/.../page.tsx (server component)
  async function deleteReportAction(reportId: string) {
    "use server";
    await deleteReport(reportId);          // server-only api helper
    revalidatePath("/me/blood");
  }

  return <BloodReportActions deleteReport={deleteReportAction} />;
  ```

  ```tsx
  // components/bloodtest/BloodReportActions.tsx ("use client")
  type Props = {
    deleteReport: (reportId: string) => Promise<void>;
  };
  // …
  await props.deleteReport(reportId);      // invokes the server action
  ```
- Do **not** add `"use server"` at the top of a `lib/*-api.ts` file as a
  shortcut. It mixes "data-access helpers" with "server actions" in one
  file and surprises future readers. Define the action where it's used.
- Prefer **pre-fetching reads server-side** and passing the data as a
  prop over exposing the read as a server action that the client re-calls.
  Use server actions for mutations and for searches that genuinely need
  fresh data per user interaction.

## User feedback (confirms + toasts)
- **Never use `window.confirm`, `window.alert`, or `window.prompt`.** They
  break out of the design system and look tacked-on.
- For confirmations: `const ok = await useConfirm()({ title, description,
  confirmLabel, tone: "danger" })`. Returns a Promise&lt;boolean&gt;.
- For success/error/info messages: `const { toast } = useToast()` →
  `toast.success("Saved")`, `toast.error("Something broke",
  { description })`, `toast.info(...)`. Toasts auto-dismiss after 4s.
- Both hooks live in `components/ui/` and are mounted globally via
  `<Providers>` in `app/layout.tsx` — they're available anywhere in the
  client tree.
- Destructive actions (delete, disconnect, anything irreversible) should
  use `tone: "danger"` on the confirm and a success toast on completion.
