"use client";

import { useState } from "react";
import type { DrugForm } from "@/lib/types/medication";

interface DrugImageProps {
  imageUrl: string | null;
  fallbackUrl: string | null;
  form: DrugForm;
  name: string;
  className?: string;
}

// Default fallback images by form type
const FALLBACK_IMAGES: Record<DrugForm, string> = {
  INJECTABLE_VIAL: "/fallbacks/injectable-vial.png",
  TABLET: "/fallbacks/tablet.png",
  CAPSULE: "/fallbacks/capsule.png",
  SOFTGEL: "/fallbacks/softgel.png",
  CREAM: "/fallbacks/cream.png",
  PATCH: "/fallbacks/patch.png",
  LIQUID: "/fallbacks/liquid.png",
  POWDER: "/fallbacks/powder.png",
};

// Material Symbols icon names for different drug forms
const FORM_ICONS: Record<DrugForm, string> = {
  TABLET: "medication",
  CAPSULE: "pill",
  SOFTGEL: "pill",
  INJECTABLE_VIAL: "vaccines",
  CREAM: "humidity_mid",
  PATCH: "medical_mask",
  LIQUID: "water_drop",
  POWDER: "blender",
};

// Placeholder using Google Material Symbols
function PlaceholderIcon({ form }: { form: DrugForm }) {
  const iconName = FORM_ICONS[form] || "medication";

  return (
    <span
      className="material-symbols-rounded text-tertiary/50"
      style={{ fontSize: "48px" }}
    >
      {iconName}
    </span>
  );
}

export function DrugImage({ imageUrl, fallbackUrl, form, name, className = "" }: DrugImageProps) {
  const [error, setError] = useState(false);

  // If no imageUrl or it failed to load, show placeholder
  if (!imageUrl || error) {
    return (
      <div className={`flex items-center justify-center bg-canvas-sunken ${className}`}>
        <PlaceholderIcon form={form} />
      </div>
    );
  }

  // Show primary image
  return (
    <img
      src={imageUrl}
      alt={name}
      className={`object-cover ${className}`}
      onError={() => setError(true)}
    />
  );
}
