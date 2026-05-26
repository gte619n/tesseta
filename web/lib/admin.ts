import { auth } from '@/auth';
import { redirect } from 'next/navigation';

// Configure admin emails here. In production, this should come from
// environment variables or a database.
const ADMIN_EMAILS = [
  'admin@example.com',
  'evan.ruff@gmail.com',
  // Add more admin emails as needed
];

export async function isAdmin(): Promise<boolean> {
  const session = await auth();
  if (!session?.user?.email) return false;
  return ADMIN_EMAILS.includes(session.user.email);
}

export async function requireAdmin() {
  const admin = await isAdmin();
  if (!admin) {
    redirect('/');
  }
}
