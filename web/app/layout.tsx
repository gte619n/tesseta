import "./globals.css";
import type { Metadata } from "next";
import { Instrument_Sans, JetBrains_Mono } from "next/font/google";
import { Providers } from "@/components/ui/Providers";
import { IconFonts } from "@/components/ui/IconFonts";

const instrumentSans = Instrument_Sans({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-sans",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "tesseta",
    template: "tesseta: %s",
  },
  description: "A health record made of small tiles.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="en"
      className={`${instrumentSans.variable} ${jetbrainsMono.variable}`}
    >
      <head>
        {/* Preconnect to CDN origins so the TCP+TLS handshake is done before
            the async stylesheets are fetched — eliminates ~100-300 ms of
            connection latency from the critical path. */}
        <link rel="preconnect" href="https://cdn.jsdelivr.net" />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link
          rel="preconnect"
          href="https://fonts.gstatic.com"
          crossOrigin="anonymous"
        />
        {/* Non-blocking icon/symbol font loader (async CSS pattern). */}
        <IconFonts />
      </head>
      <body className="font-sans antialiased bg-canvas text-primary">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
