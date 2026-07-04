import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ModalBackdrop } from "@/components/ui/ModalBackdrop";

// Phase-5 a11y: the shared modal primitive must expose dialog semantics, close
// on Escape, and move focus into the dialog — one fix covering all 16 modals.
describe("ModalBackdrop", () => {
  it("exposes role=dialog with aria-modal and an accessible name", () => {
    render(
      <ModalBackdrop onClose={() => {}} label="Edit reading">
        <button>Save</button>
      </ModalBackdrop>,
    );
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleName("Edit reading");
  });

  it("closes on Escape", async () => {
    const onClose = vi.fn();
    render(
      <ModalBackdrop onClose={onClose} label="d">
        <button>Save</button>
      </ModalBackdrop>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("moves focus to the first focusable element on open", () => {
    render(
      <ModalBackdrop onClose={() => {}} label="d">
        <button>First</button>
        <button>Second</button>
      </ModalBackdrop>,
    );
    expect(screen.getByRole("button", { name: "First" })).toHaveFocus();
  });

  it("prefers aria-labelledby when provided", () => {
    render(
      <ModalBackdrop onClose={() => {}} labelledBy="title">
        <h2 id="title">Delete report</h2>
        <button>Confirm</button>
      </ModalBackdrop>,
    );
    expect(screen.getByRole("dialog")).toHaveAccessibleName("Delete report");
  });
});
