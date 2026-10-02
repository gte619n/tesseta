import { signOut } from "@/auth";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Account unavailable");

// IMPL-MULTIUSER-01 P1.4 — a SUSPENDED/DISABLED account is locked out. apiFetch
// redirects here on a 403 carrying `X-Account-Status: account-suspended` or
// `account-disabled`. The user cannot self-recover; they can only sign out.
export default function SuspendedPage() {
  async function doSignOut() {
    "use server";
    await signOut({ redirectTo: "/auth/signin" });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
      <div className="w-[360px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <div className="mb-4 flex h-10 w-10 items-center justify-center rounded-md bg-canvas-muted text-secondary">
          <i className="ti ti-lock text-xl" aria-hidden />
        </div>
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Account unavailable
        </h1>
        <p className="mt-2 text-[13px] text-secondary">
          Your account is currently unavailable. If you think this is a mistake,
          contact an administrator.
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
