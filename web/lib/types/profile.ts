/** The current authenticated user, as returned by GET /api/me. */
export type WhoAmI = {
  userId: string;
  email: string | null;
  displayName: string | null;
  heightCm: number | null;
  // "MALE" | "FEMALE" | null — used for the Mifflin-St Jeor calorie estimate.
  biologicalSex: string | null;
  // ISO date "YYYY-MM-DD" | null.
  dateOfBirth: string | null;
};

/** Body-detail fields PATCHed to /api/me (any subset; omitted stays unchanged). */
export type ProfileUpdate = {
  heightCm?: number | null;
  biologicalSex?: string | null;
  dateOfBirth?: string | null;
};
