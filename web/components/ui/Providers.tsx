"use client";

import { ConfirmProvider } from "./ConfirmDialog";
import { Toaster } from "./Toast";
import { UnitsProvider } from "./UnitsProvider";

// Single client-side wrapper for our global UI providers. The root
// layout stays a server component; this is the one client boundary
// that gives the rest of the app access to useToast() / useConfirm() /
// useUnits().
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <UnitsProvider>
      <Toaster>
        <ConfirmProvider>{children}</ConfirmProvider>
      </Toaster>
    </UnitsProvider>
  );
}
