import { getAdminExerciseReview } from '@/lib/exercise-admin-api';
import { ExerciseSubTabs } from '@/components/admin/ExerciseSubTabs';

export const dynamic = 'force-dynamic';

export default async function ExercisesLayout({ children }: { children: React.ReactNode }) {
  const review = await getAdminExerciseReview().catch(() => []);
  return (
    <div>
      <div className="border-b border-border-default bg-surface">
        <div className="container mx-auto max-w-7xl px-4">
          <ExerciseSubTabs reviewCount={review.length} />
        </div>
      </div>
      {children}
    </div>
  );
}
