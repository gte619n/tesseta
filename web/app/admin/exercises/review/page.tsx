import { redirect } from 'next/navigation';
import { pageMetadata } from '@/lib/page-metadata';

export const dynamic = 'force-dynamic';

// This route only redirects, but the check:titles guard requires every page to
// declare a title; harmless here and correct if it ever renders content.
export const metadata = pageMetadata('Exercise Review');

// IMPL-20: the Review workflow is folded into a "Needs review" filter preset on
// the catalog. The route is kept as a redirect so existing links/bookmarks
// still land on the (filtered) catalog. The review workflow itself — the media
// editor + approve flow — is reachable per-exercise via the detail drawer.
export default function AdminExerciseReviewPage() {
  redirect('/admin/exercises/catalog?preset=needs-review');
}
