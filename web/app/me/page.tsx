import type { Metadata } from "next";
import { signOut } from "@/auth";

export const metadata: Metadata = {
  title: "Dashboard",
};
import { apiJson } from "@/lib/api";

type WhoAmI = {
  userId: string;
  email: string | null;
  displayName: string | null;
};

export const dynamic = "force-dynamic";

export default async function MePage() {
  const me = await apiJson<WhoAmI>("/api/me");

  async function doSignOut() {
    "use server";
    await signOut({ redirectTo: "/auth/signin" });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
      <div className="w-[480px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Signed in
        </h1>
        <dl className="mt-5 grid grid-cols-[120px_1fr] gap-y-2 font-mono text-[12px] tabular">
          <dt className="text-tertiary">user id</dt>
          <dd className="text-primary">{me.userId}</dd>
          <dt className="text-tertiary">email</dt>
          <dd className="text-primary">{me.email ?? "—"}</dd>
          <dt className="text-tertiary">display name</dt>
          <dd className="text-primary">{me.displayName ?? "—"}</dd>
        </dl>
        <form action={doSignOut} className="mt-6">
          <button
            type="submit"
            className="cursor-pointer rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2 text-[13px] font-medium text-primary"
          >
            Sign out
          </button>
        </form>
      </div>
    </main>
  );
}
