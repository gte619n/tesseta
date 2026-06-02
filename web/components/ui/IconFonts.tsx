/**
 * Loads the icon / symbol webfonts.
 *
 * We use React 19's managed-stylesheet support (the `precedence` prop): React
 * hoists these into <head>, de-duplicates them, and — crucially — applies them
 * reliably. The previous approach loaded each sheet with `media="print"` and an
 * `onLoad` handler that promoted it to `media="all"`, but under React 19's
 * hoisted-<link> handling that onLoad did not fire, so the sheets stayed at
 * `media="print"` and every `ti …` glyph (and Material Symbol) rendered blank.
 *
 * The root layout still `preconnect`s to both origins, so the fetch cost of
 * these render-blocking sheets stays small.
 */
export function IconFonts() {
  return (
    <>
      {/* Tabler Icons webfont */}
      <link
        rel="stylesheet"
        href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3/dist/tabler-icons.min.css"
        precedence="default"
      />

      {/* Material Symbols Rounded */}
      <link
        rel="stylesheet"
        href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,1,0"
        precedence="default"
      />
    </>
  );
}
