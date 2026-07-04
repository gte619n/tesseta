import type { NextConfig } from "next";

// Security response headers applied to every route. Clickjacking, MIME-sniffing,
// referrer-leak, and transport protections for a PHI-adjacent app. The CSP is
// deliberately conservative: frame-ancestors/object-src/base-uri harden common
// vectors without a nonce pipeline (a full script-src CSP would need per-request
// nonces and is tracked separately) — Next's own inline scripts keep working.
const SECURITY_HEADERS = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  {
    key: "Strict-Transport-Security",
    value: "max-age=63072000; includeSubDomains; preload",
  },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  {
    key: "Content-Security-Policy",
    value: "frame-ancestors 'none'; object-src 'none'; base-uri 'self'",
  },
];

const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  typedRoutes: true,
  async headers() {
    return [{ source: "/:path*", headers: SECURITY_HEADERS }];
  },
  images: {
    remotePatterns: [
      // Drug, equipment, gym cover, and meal/food images are stored in GCS and
      // served from public-object URLs (see backend *ImageStorage.publicUrl()).
      {
        protocol: "https",
        hostname: "storage.googleapis.com",
      },
      // Google account avatars (next-auth session.user.image).
      {
        protocol: "https",
        hostname: "lh3.googleusercontent.com",
      },
    ],
  },
  experimental: {
    serverActions: {
      bodySizeLimit: "5mb",
    },
  },
};

export default nextConfig;
