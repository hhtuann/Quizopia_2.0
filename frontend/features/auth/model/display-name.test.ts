import { describe, expect, it } from "vitest";
import {
  displayNameSchema,
  normalizeDisplayName,
  optionalDisplayName,
} from "./display-name";

describe("display name backend compatibility", () => {
  it("normalizes Vietnamese NFC before counting UTF-16 units and strips Java whitespace", () => {
    expect(normalizeDisplayName("\u2002 Nguyễn An \u3000")).toBe("Nguyễn An");
    expect(displayNameSchema.safeParse("e\u0301".repeat(100)).success).toBe(
      true,
    );
    expect(displayNameSchema.safeParse("😀".repeat(50)).success).toBe(true);
    expect(displayNameSchema.safeParse("😀".repeat(51)).success).toBe(false);
    expect(normalizeDisplayName("\u00a0An\u00a0")).toBe("\u00a0An\u00a0");
  });
  it.each([" ", "\u3000", "a".repeat(101), "An\u0000", "An\u0085", "An\nBình"])(
    "rejects invalid input %j",
    (value) => {
      expect(displayNameSchema.safeParse(value).success).toBe(false);
    },
  );
  it("supports explicit empty clearing without inventing a name", () => {
    expect(displayNameSchema.safeParse("").success).toBe(true);
    expect(optionalDisplayName("")).toBeNull();
  });
});
