import { signOut } from "@/auth";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Pending approval");

// IMPL-MULTIUSER-01 P1.3 — a non-allowlisted Google sign-in authenticates but
// lands in PENDING_APPROVAL with no app data. apiFetch redirects here on a 403
// carrying `X-Account-Status: account-pending`.
export default function PendingApprovalPage() {
  async function doSignOut() {
    "use server";
    await signOut({ redirectTo: "/auth/signin" });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
      <div className="w-[360px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <div className="mb-4 flex h-10 w-10 items-center justify-center rounded-md bg-canvas-muted text-secondary">
          <i className="ti ti-clock-hour-4 text-xl" aria-hidden />
        </div>
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Request received
        </h1>
        <p className="mt-2 text-[13px] text-secondary">
          Your account is pending approval. An administrator will review your
          request — you&apos;ll get access once it&apos;s approved. Try signing
          in again later.
        </p>
        <form action={doSignOut} className="mt-6">
          <button
            type="submit"
            className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2.5 text-[13px] font-medium text-primary"
          >
            <i className="ti ti-logout text-[14px]" aria-hidden />
            Sign out
          </button>
        </form>
      </div>
    </main>
  );
}
