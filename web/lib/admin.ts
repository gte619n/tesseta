import { auth } from '@/auth';
import { redirect } from 'next/navigation';

// Built-in admin emails, extended by the ADMIN_EMAILS env var (comma-separated)
// so deploys — and the UAT run (infra/scripts/uat.sh) — can grant admin without
// a code change. Keep this in sync with the backend's app.admin.emails.
const BUILT_IN_ADMIN_EMAILS = [
  'admin@example.com',
  'evan.ruff@gmail.com',
];

function adminEmails(): string[] {
  const fromEnv = (process.env.ADMIN_EMAILS ?? '')
    .split(',')
    .map((e) => e.trim())
    .filter(Boolean);
  return [...BUILT_IN_ADMIN_EMAILS, ...fromEnv];
}

export async function isAdmin(): Promise<boolean> {
  const session = await auth();
  if (!session?.user?.email) return false;
  return adminEmails().includes(session.user.email);
}

export async function requireAdmin() {
  const admin = await isAdmin();
  if (!admin) {
    redirect('/');
  }
}
