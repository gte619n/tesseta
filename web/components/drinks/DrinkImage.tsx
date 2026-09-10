import Image from "next/image";
import type { ImageStatus } from "@/lib/types/nutrition";

type Size = 40 | 48 | 56;

const SIZE_CLASS: Record<Size, string> = {
  40: "h-10 w-10",
  48: "h-12 w-12",
  56: "h-14 w-14",
};

/**
 * Thumbnail for a drink's generated glassware image (IMPL-DRINK-01).
 *
 * - READY + url → the image
 * - PENDING → a spinner (generation in flight)
 * - NONE / FAILED / unknown → a glassware placeholder
 *
 * Mirrors {@link FoodImage} but swaps the utensil fallback for a cocktail-glass
 * icon so a drink whose image is still generating (or failed) still reads as a
 * drink. Listing/logging never depends on the image being READY.
 */
export function DrinkImage({
  imageUrl,
  imageStatus,
  size = 48,
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
    return (
      <div
        className={`${sizeClass} flex shrink-0 items-center justify-center rounded-[6px] bg-canvas-sunken`}
        aria-label="Generating image"
      >
        <i className="ti ti-loader-2 animate-spin text-[15px] text-accent" aria-hidden />
      </div>
    );
  }

  // NONE / FAILED / undefined — glassware placeholder.
  return (
    <div
      className={`${sizeClass} flex shrink-0 items-center justify-center rounded-[6px] bg-canvas-sunken`}
      aria-hidden
    >
      <i className="ti ti-glass-cocktail text-[16px] text-tertiary" />
    </div>
  );
}
