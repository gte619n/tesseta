import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";

// Phase-0 sample: proves React Testing Library + jsdom + user-event + jest-dom
// matchers all work under Vitest, so Phase 5 can write component/a11y tests.
function Counter() {
  const [n, setN] = useState(0);
  return (
    <button type="button" onClick={() => setN((v) => v + 1)}>
      count: {n}
    </button>
  );
}

describe("RTL harness", () => {
  it("renders and responds to user events", async () => {
    render(<Counter />);
    const button = screen.getByRole("button", { name: /count: 0/ });
    expect(button).toBeInTheDocument();
    await userEvent.click(button);
    expect(screen.getByRole("button", { name: /count: 1/ })).toBeInTheDocument();
  });
});
