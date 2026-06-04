"use client";

import { PdfUploadDropzone } from "@/components/ui/PdfUploadDropzone";

// Blood-test (lab result) PDF uploader. Thin wrapper over the shared
// <PdfUploadDropzone>; see BloodTestController for the /api/blood/upload
// SSE contract.
export function BloodTestUploadButton() {
  return (
    <PdfUploadDropzone
      uploadUrl="/api/blood/upload"
      triggerLabel="Drop Lab Result PDFs"
      triggerTitle="Drag blood test PDFs onto the page, or click to choose"
      overlayLabel="Drop your blood test PDFs"
      itemNoun="report"
    />
  );
}
