import { describe, expect, it } from "vitest";
import { authoringMessages } from "../../../lib/i18n/messages/authoring";
import { commonMessages } from "../../../lib/i18n/messages/common";
import { assessmentMessages } from "../../../lib/i18n/messages/assessment";
import { studentMessages } from "../../../lib/i18n/messages/student";
import { monitoringMessages } from "../../../lib/i18n/messages/monitoring";

describe("authoring catalog integrity", () => {
  it("has no duplicate keys across catalogs", () => {
    const others = {
      ...commonMessages,
      ...assessmentMessages,
      ...studentMessages,
      ...monitoringMessages,
    };
    const authoringKeys = Object.keys(authoringMessages);
    expect(authoringKeys.filter((key) => key in others)).toEqual([]);
    expect(
      new Set(authoringKeys).size,
      `unique keys: ${authoringKeys.length}`,
    ).toBe(authoringKeys.length);
    console.log("authoring key count:", authoringKeys.length);
    for (const [key, value] of Object.entries(authoringMessages)) {
      expect(value.length, key).toBeGreaterThan(0);
      expect(key.length).toBeGreaterThan(0);
    }
  });
});
