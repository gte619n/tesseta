"use client";

/**
 * Loads icon/symbol fonts asynchronously so they don't block First Contentful
 * Paint.  The `media="print"` trick causes the browser to fetch the stylesheet
 * at low priority (non-render-blocking), then the onLoad handler promotes it
 * to `media="all"` so it applies normally once the sheet arrives.
 *
 * A <noscript> fallback re-instates the blocking link for JS-disabled clients.
 *
 * Rendered inside <head> by the root Server-Component layout; the `"use
 * client"` directive is required for the onLoad JSX event handler.
 */
export function IconFonts() {
  return (
    <>
      {/* Tabler Icons webfont — async, non-blocking */}
      <link
        rel="stylesheet"
        href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3/dist/tabler-icons.min.css"
        media="print"
        onLoad={(e) => {
          const el = e.currentTarget as HTMLLinkElement;
          el.media = "all";
        }}
      />
      <noscript>
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3/dist/tabler-icons.min.css"
        />
      </noscript>

      {/* Material Symbols Rounded — async, non-blocking */}
      <link
        rel="stylesheet"
        href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,1,0"
        media="print"
        onLoad={(e) => {
          const el = e.currentTarget as HTMLLinkElement;
          el.media = "all";
        }}
      />
      <noscript>
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,1,0"
        />
      </noscript>
    </>
  );
}
