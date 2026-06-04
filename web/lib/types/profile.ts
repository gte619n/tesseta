/** The current authenticated user, as returned by GET /api/me. */
export type WhoAmI = {
  userId: string;
  email: string | null;
  displayName: string | null;
  heightCm: number | null;
};
