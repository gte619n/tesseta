import { signIn } from "@/auth";
import { pageMetadata } from "@/lib/page-metadata";

export const metadata = pageMetadata("Sign in");

export default async function SignInPage({
  searchParams,
}: {
  searchParams: Promise<{ callbackUrl?: string }>;
}) {
  const params = await searchParams;
  const callbackUrl = params.callbackUrl ?? "/";

  async function doSignIn() {
    "use server";
    await signIn("google", { redirectTo: callbackUrl });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-canvas p-8">
      <div className="w-[360px] rounded-[14px] border-[0.5px] border-border-default bg-surface px-7 py-8 shadow-[0_24px_64px_rgba(0,0,0,0.08)]">
        <h1 className="m-0 text-[22px] font-medium tracking-[-0.015em] text-primary">
          Sign in
        </h1>
        <p className="mt-2 text-[13px] text-secondary">
          Continue with your Google account.
        </p>
        <form action={doSignIn} className="mt-6">
          <button
            type="submit"
            className="flex w-full cursor-pointer items-center justify-center gap-2 rounded-md border-[0.5px] border-border-default bg-canvas px-4 py-2.5 text-[13px] font-medium text-primary"
          >
            <i className="ti ti-brand-google text-[14px]" aria-hidden />
            Continue with Google
          </button>
        </form>
      </div>
    </main>
  );
}
