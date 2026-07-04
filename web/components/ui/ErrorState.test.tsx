import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ErrorState } from "@/components/ui/ErrorState";

// Phase-5 resilience: the recovery card error boundaries render must announce
// itself and let the user retry, instead of the page blanking.
describe("ErrorState", () => {
  it("announces itself as an alert with a message", () => {
    render(<ErrorState description="Couldn't load your data." />);
    expect(screen.getByRole("alert")).toHaveTextContent("Couldn't load your data.");
  });

  it("invokes onRetry when the retry button is pressed", async () => {
    const onRetry = vi.fn();
    render(<ErrorState onRetry={onRetry} />);
    await userEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("omits the retry button when no handler is given", () => {
    render(<ErrorState />);
    expect(screen.queryByRole("button", { name: /try again/i })).toBeNull();
  });
});
