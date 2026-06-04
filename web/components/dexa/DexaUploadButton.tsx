"use client";

import { PdfUploadDropzone } from "@/components/ui/PdfUploadDropzone";

// DEXA scan PDF uploader. Thin wrapper over the shared <PdfUploadDropzone>;
// see DexaScanController for the /api/dexa/upload SSE contract.
export function DexaUploadButton() {
  return (
    <PdfUploadDropzone
      uploadUrl="/api/dexa/upload"
      triggerLabel="Drop DEXA PDFs"
      triggerTitle="Drag DEXA PDFs onto the page, or click to choose"
      overlayLabel="Drop your DEXA PDFs"
      itemNoun="scan"
    />
  );
}
