import { redirect } from 'next/navigation';

export const dynamic = 'force-dynamic';

// IMPL-20: the Review workflow is folded into a "Needs review" filter preset on
// the catalog. The route is kept as a redirect so existing links/bookmarks
// still land on the (filtered) catalog. The review workflow itself — the media
// editor + approve flow — is reachable per-exercise via the detail drawer.
export default function AdminExerciseReviewPage() {
  redirect('/admin/exercises/catalog?preset=needs-review');
}
