import { describe, expect, it } from "vitest";

import { formatMoney, formatScore, humanize, maskCard, mccLabel, timeAgo } from "@/lib/format";

describe("format", () => {
  it("formats integer cents as currency without float drift", () => {
    expect(formatMoney(4250)).toBe("$42.50");
    expect(formatMoney(10)).toBe("$0.10");
    expect(formatMoney(123456789)).toBe("$1,234,567.89");
  });

  it("only ever shows the last 4 digits", () => {
    expect(maskCard("4242")).toBe("•••• 4242");
    expect(maskCard(null)).toBe("••••");
    expect(maskCard("4242424242424242")).toBe("••••"); // anything that isn't exactly 4 digits is hidden
  });

  it("formats scores and missing values", () => {
    expect(formatScore(0.87654)).toBe("0.877");
    expect(formatScore(null)).toBe("—");
  });

  it("humanizes codes", () => {
    expect(humanize("INSUFFICIENT_CREDIT")).toBe("Insufficient credit");
    expect(humanize(null)).toBe("—");
  });

  it("labels merchant categories", () => {
    expect(mccLabel("5999")).toBe("Gift cards");
    expect(mccLabel("1234")).toBe("MCC 1234");
  });

  it("describes relative time", () => {
    const now = new Date("2026-03-01T12:00:00Z");
    expect(timeAgo("2026-03-01T11:59:30Z", now)).toBe("30s ago");
    expect(timeAgo("2026-03-01T11:00:00Z", now)).toBe("1h ago");
    expect(timeAgo("2026-02-25T12:00:00Z", now)).toBe("4d ago");
  });
});
