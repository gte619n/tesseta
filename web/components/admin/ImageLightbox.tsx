"use client";

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { ModalBackdrop } from '@/components/ui/ModalBackdrop';

type ImageLightboxProps = {
  src: string | null;
  alt: string;
  onClose: () => void;
};

export function ImageLightbox({ src, alt, onClose }: ImageLightboxProps) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!src) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [src, onClose]);

  if (!mounted || !src) return null;

  return createPortal(
    <ModalBackdrop
      onClose={onClose}
      className="fixed inset-0 z-[200] flex items-center justify-center bg-black/80 p-6"
      contentClassName="max-h-[90vh] max-w-[90vw] rounded-lg shadow-2xl"
    >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={src}
          alt={alt}
          className="block max-h-[90vh] max-w-[90vw] rounded-lg object-contain"
        />
    </ModalBackdrop>,
    document.body,
  );
}
