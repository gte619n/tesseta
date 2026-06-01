import Image from "next/image";
import type { ImageStatus } from "@/lib/types/nutrition";

type Size = 36 | 40 | 48 | 64;

const SIZE_CLASS: Record<Size, string> = {
  36: "h-9 w-9",
  40: "h-10 w-10",
  48: "h-12 w-12",
  64: "h-16 w-16",
};

/**
 * Thumbnail for a food's generated studio image.
 *
 * - READY + url → the image
 * - PENDING → a spinner (generation in flight)
 * - NONE / FAILED / unknown → a neutral utensil placeholder
 *
 * Shared by the add-food modal and logged-entry rows so the placeholder
 * behaviour stays consistent everywhere a food can appear.
 */
export function FoodImage({
  imageUrl,
  imageStatus,
  size = 40,
}: {
  imageUrl?: string | null;
  imageStatus?: ImageStatus;
  size?: Size;
}) {
  const sizeClass = SIZE_CLASS[size];

  if (imageStatus === "READY" && imageUrl) {
    return (
      <Image
        src={imageUrl}
        alt=""
        width={size}
        height={size}
        className={`${sizeClass} shrink-0 rounded-[6px] object-cover`}
      />
    );
  }

  if (imageStatus === "PENDING") {
    // Image is generating — show a spinner so the row reads as "working".
    return (
      <div
        className={`${sizeClass} flex shrink-0 items-center justify-center rounded-[6px] bg-canvas-sunken`}
        aria-label="Generating image"
      >
        <i className="ti ti-loader-2 animate-spin text-[14px] text-accent" aria-hidden />
      </div>
    );
  }

  // NONE / FAILED / undefined — neutral utensil placeholder for not-yet-
  // generated food.
  return (
    <div
      className={`${sizeClass} flex shrink-0 items-center justify-center rounded-[6px] bg-canvas-sunken`}
      aria-hidden
    >
      <i className="ti ti-tools-kitchen-2 text-[14px] text-tertiary" />
    </div>
  );
}
