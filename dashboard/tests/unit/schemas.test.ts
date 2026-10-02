import { describe, expect, it } from "vitest";

import { authorizationPageSchema, authorizationSchema } from "@/lib/schemas";

import { authorization } from "./fixtures";

describe("schemas", () => {
  it("accepts a valid authorization page", () => {
    const page = { content: [authorization()], page: 0, size: 25, totalElements: 1, totalPages: 1 };
    expect(authorizationPageSchema.parse(page).content[0].merchantName).toBe("CardMart Gift Cards");
  });

  it("rejects an unknown status instead of rendering it", () => {
    expect(() => authorizationSchema.parse({ ...authorization(), status: "MAYBE" })).toThrow();
  });

  it("rejects a response missing required fields", () => {
    const withoutAmount: Record<string, unknown> = { ...authorization() };
    delete withoutAmount.amountMinor;
    expect(() => authorizationSchema.parse(withoutAmount)).toThrow();
  });
});
