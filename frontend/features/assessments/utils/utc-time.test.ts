import { describe, expect, it } from "vitest";
import {
  formatUtc,
  instantMicroseconds,
  instantToUtcField,
  utcFieldToInstant,
} from "./utc-time";

describe("UTC Publication scheduling", () => {
  it("converts minute and microsecond input to explicit UTC instants", () => {
    expect(utcFieldToInstant("2026-10-16T08:30")).toBe("2026-10-16T08:30:00Z");
    expect(utcFieldToInstant("2026-10-16T08:30:12.123456")).toBe(
      "2026-10-16T08:30:12.123456Z",
    );
    expect(instantToUtcField("2026-10-16T15:30:12.123456+07:00")).toBe(
      "2026-10-16T08:30:12.123456",
    );
    expect(formatUtc("2026-10-16T15:30:12.123456+07:00")).toBe(
      "2026-10-16 08:30:12.123456 UTC",
    );
  });

  it("uses UTC fields even at ambiguous or nonexistent regional DST wall times", () => {
    expect(utcFieldToInstant("2026-03-08T02:30")).toBe("2026-03-08T02:30:00Z");
    expect(utcFieldToInstant("2026-11-01T01:30")).toBe("2026-11-01T01:30:00Z");
    expect(instantMicroseconds("2026-11-01T01:30:00-04:00")).not.toBe(
      instantMicroseconds("2026-11-01T01:30:00-05:00"),
    );
  });

  it.each([
    "2026-02-29T12:00",
    "2026-04-31T12:00",
    "2026-00-01T12:00",
    "2026-13-01T12:00",
    "2026-10-00T12:00",
    "2026-10-16T24:00",
    "2026-10-16T12:60",
    "2026-10-16T12:00:60",
    "2026-10-16T12:00:00.1234567",
    "2026-10-16T12:00Z",
    "2026-10-16T12:00+07:00",
    "0000-01-01T00:00",
  ])("rejects invalid or ambiguous field %s", (value) => {
    expect(utcFieldToInstant(value)).toBeNull();
  });

  it.each([
    "2026-02-29T12:00:00Z",
    "2026-10-16T12:00:00",
    "2026-10-16T12:00:00+18:01",
    "2026-10-16T12:00:00+19:00",
    "2026-10-16T12:00:00+00:60",
    "2026-10-16T12:00:00.1234567Z",
    "2026-10-16 12:00:00Z",
  ])("rejects invalid wire instant %s", (value) => {
    expect(instantMicroseconds(value)).toBeNull();
    expect(instantToUtcField(value)).toBe("");
    expect(formatUtc(value)).toBe("Invalid instant");
  });

  it("preserves exact fractions, leap days and years below 100", () => {
    expect(instantMicroseconds("2028-02-29T00:00:00Z")).not.toBeNull();
    expect(instantToUtcField("0099-01-01T00:00:00Z")).toBe(
      "0099-01-01T00:00:00",
    );
    expect(instantToUtcField("1969-12-31T23:59:59.999999Z")).toBe(
      "1969-12-31T23:59:59.999999",
    );
    expect(instantMicroseconds("1970-01-01T00:00:00.000001Z")).toBe(BigInt(1));
    expect(instantMicroseconds("1970-01-01T00:00:00.000002Z")).toBe(BigInt(2));
    expect(instantMicroseconds("1970-01-01T07:00:00+07:00")).toBe(BigInt(0));
  });
});
