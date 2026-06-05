import { signIn } from "@/auth";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("UAT dev sign-in");

// UAT / local end-to-end only. A no-Google sign-in entry point for the Selenium
// suite, active only when UAT_AUTH_ENABLED=1 (the "uat-dev" Credentials provider
// is otherwise absent and signIn() would throw). Fill in a test identity and
// submit to mint a backend session via POST /api/auth/dev-login. Stable
// data-testid hooks let the harness drive it without scraping styled markup.
export default async function DevSignInPage({
  searchParams,
}: {
  searchParams: Promise<{ callbackUrl?: string; userId?: string; email?: string; name?: string }>;
}) {
  const params = await searchParams;
  const raw = params.callbackUrl ?? "/";
  const callbackUrl =
    raw.startsWith("/") && !raw.startsWith("//") && !raw.startsWith("/api")
      ? raw
      : "/";

  if (process.env.UAT_AUTH_ENABLED !== "1") {
    return (
      <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
        <div
          data-testid="dev-signin-disabled"
          className="w-[360px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8"
        >
          <h1 className="m-0 text-[22px] font-medium text-primary">Dev sign-in disabled</h1>
          <p className="mt-2 text-[13px] text-secondary">
            Set <code>UAT_AUTH_ENABLED=1</code> to enable the UAT dev sign-in.
          </p>
        </div>
      </main>
    );
  }

  async function doDevSignIn(formData: FormData) {
    "use server";
    const userId = String(formData.get("userId") ?? "").trim();
    const email = String(formData.get("email") ?? "").trim();
    const name = String(formData.get("name") ?? "").trim();
    await signIn("uat-dev", { userId, email, name, redirectTo: callbackUrl });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
      <div className="w-[360px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          UAT dev sign-in
        </h1>
        <p className="mt-2 text-[13px] text-secondary">Sign in as a test user (no Google).</p>
        <form action={doDevSignIn} className="mt-6 flex flex-col gap-3" data-testid="dev-signin-form">
          <input
            name="userId"
            data-testid="dev-userId"
            defaultValue={params.userId ?? "uat-user"}
            placeholder="User ID"
            className="rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary"
          />
          <input
            name="email"
            data-testid="dev-email"
            defaultValue={params.email ?? ""}
            placeholder="email (optional)"
            className="rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary"
          />
          <input
            name="name"
            data-testid="dev-name"
            defaultValue={params.name ?? ""}
            placeholder="name (optional)"
            className="rounded-md border-[0.5px] border-border-default bg-canvas px-3 py-2 text-[13px] text-primary"
          />
          <button
            type="submit"
            data-testid="dev-signin-submit"
            className="mt-2 flex w-full cursor-pointer items-center justify-center gap-2 rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2.5 text-[13px] font-medium text-primary"
          >
            Sign in
          </button>
        </form>
      </div>
    </main>
  );
}
