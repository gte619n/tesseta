import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  typedRoutes: true,
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
