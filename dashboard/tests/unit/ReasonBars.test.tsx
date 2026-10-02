import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ReasonBars } from "@/components/ReasonBars";

describe("ReasonBars", () => {
  it("scales bars to the strongest reason", () => {
    render(
      <ReasonBars
        reasons={[
          { code: "HIGH_VELOCITY", description: "Many transactions", contribution: 2 },
          { code: "AMOUNT_SPIKE", description: "Amount spike", contribution: 1 },
        ]}
      />,
    );
    expect(screen.getByRole("meter", { name: "HIGH_VELOCITY contribution" }).getAttribute("aria-valuenow")).toBe("100");
    expect(screen.getByRole("meter", { name: "AMOUNT_SPIKE contribution" }).getAttribute("aria-valuenow")).toBe("50");
  });

  it("lists fallback-rule reasons without bars", () => {
    render(<ReasonBars reasons={[{ code: "LARGE_AMOUNT", description: "Large transaction amount", contribution: null }]} />);
    expect(screen.getByText("Large transaction amount")).toBeTruthy();
    expect(screen.queryByRole("meter")).toBeNull();
  });

  it("says when there are no reasons", () => {
    render(<ReasonBars reasons={[]} />);
    expect(screen.getByText("No risk reasons recorded.")).toBeTruthy();
  });
});
