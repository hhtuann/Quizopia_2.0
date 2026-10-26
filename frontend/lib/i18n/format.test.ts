import { describe, expect, it } from "vitest";
import { DISPLAY_TIME_ZONE, formatInstant, formatInstantRange } from "./core";

const microsecondInstant = "2026-10-25T08:38:37.628524Z";

describe("formatInstant", () => {
  it("renders the vi default pattern HH:mm, dd/MM/yyyy", () => {
    expect(formatInstant("vi", microsecondInstant)).toBe("15:38, 25/10/2026");
  });

  it("renders the en default via Intl en-GB medium", () => {
    expect(formatInstant("en", microsecondInstant)).toBe("25 Oct 2026, 15:38");
  });

  it("uses 24-hour time in both locales across day rollover", () => {
    expect(formatInstant("vi", "2026-10-25T18:00:00Z")).toBe(
      "01:00, 26/10/2026",
    );
    expect(formatInstant("en", "2026-10-25T18:00:00Z")).toBe(
      "26 Oct 2026, 01:00",
    );
    expect(formatInstant("vi", "2026-10-24T17:05:00Z")).toBe(
      "00:05, 25/10/2026",
    );
  });

  it("appends the explicit zone label when withTimeZone is requested", () => {
    expect(
      formatInstant("vi", microsecondInstant, { withTimeZone: true }),
    ).toBe("15:38, 25/10/2026 (giờ Việt Nam, UTC+7)");
    expect(
      formatInstant("en", microsecondInstant, { withTimeZone: true }),
    ).toBe("25 Oct 2026, 15:38 (Vietnam time, UTC+7)");
  });

  it("returns an em dash for invalid instants", () => {
    expect(formatInstant("vi", "not-a-date")).toBe("—");
    expect(formatInstant("en", "not-a-date", { withTimeZone: true })).toBe("—");
  });

  it("returns an em dash for null, undefined and empty values", () => {
    expect(formatInstant("vi", null)).toBe("—");
    expect(formatInstant("en", undefined)).toBe("—");
    expect(formatInstant("vi", "")).toBe("—");
  });

  it("truncates milliseconds and microseconds and never shows seconds by default", () => {
    expect(formatInstant("vi", "2026-10-25T08:38:37.628Z")).toBe(
      "15:38, 25/10/2026",
    );
    expect(formatInstant("en", "2026-10-25T08:38:37Z")).toBe(
      "25 Oct 2026, 15:38",
    );
    expect(formatInstant("vi", microsecondInstant)).not.toMatch(/:\d{2}:/);
    expect(formatInstant("en", microsecondInstant)).not.toMatch(/:\d{2}:/);
  });

  it("keeps a fixed +7 offset across seasons (DST-irrelevant zone)", () => {
    expect(
      formatInstant("vi", "2026-01-15T18:00:00Z", { withTimeZone: true }),
    ).toBe("01:00, 16/01/2026 (giờ Việt Nam, UTC+7)");
    expect(
      formatInstant("en", "2026-07-15T18:00:00Z", { withTimeZone: true }),
    ).toBe("16 Jul 2026, 01:00 (Vietnam time, UTC+7)");
  });

  it("accepts Date and epoch-millisecond number inputs", () => {
    expect(formatInstant("en", new Date(microsecondInstant))).toBe(
      "25 Oct 2026, 15:38",
    );
    expect(formatInstant("vi", new Date(microsecondInstant).getTime())).toBe(
      "15:38, 25/10/2026",
    );
  });

  it("supports date-only, time-only and secondary styles", () => {
    expect(formatInstant("vi", microsecondInstant, { timeStyle: "none" })).toBe(
      "25/10/2026",
    );
    expect(formatInstant("en", microsecondInstant, { timeStyle: "none" })).toBe(
      "25 Oct 2026",
    );
    expect(formatInstant("vi", microsecondInstant, { dateStyle: "none" })).toBe(
      "15:38",
    );
    expect(formatInstant("en", microsecondInstant, { dateStyle: "none" })).toBe(
      "15:38",
    );
    expect(
      formatInstant("vi", microsecondInstant, { timeStyle: "medium" }),
    ).toBe("15:38:37, 25/10/2026");
    expect(
      formatInstant("en", microsecondInstant, { timeStyle: "medium" }),
    ).toBe("25 Oct 2026, 15:38:37");
    expect(formatInstant("vi", microsecondInstant, { dateStyle: "long" })).toBe(
      "15:38, 25 tháng 10, 2026",
    );
    expect(formatInstant("en", microsecondInstant, { dateStyle: "long" })).toBe(
      "25 October 2026 at 15:38",
    );
    expect(
      formatInstant("vi", microsecondInstant, {
        dateStyle: "none",
        timeStyle: "none",
      }),
    ).toBe("—");
  });

  it("exposes the display time zone as Asia/Ho_Chi_Minh", () => {
    expect(DISPLAY_TIME_ZONE).toBe("Asia/Ho_Chi_Minh");
  });
});

describe("formatInstantRange", () => {
  it("collapses same-day windows to one date with both times (vi time first)", () => {
    expect(
      formatInstantRange("vi", microsecondInstant, "2026-10-25T10:38:00Z"),
    ).toBe("15:38, 25/10/2026 – 17:38");
  });

  it("collapses same-day windows with the en date-first order", () => {
    expect(
      formatInstantRange("en", microsecondInstant, "2026-10-25T10:38:00Z"),
    ).toBe("25 Oct 2026, 15:38 – 17:38");
  });

  it("renders both instants when the window crosses a day in the zone", () => {
    expect(
      formatInstantRange("vi", microsecondInstant, "2026-10-26T02:38:00Z"),
    ).toBe("15:38, 25/10/2026 – 09:38, 26/10/2026");
    expect(
      formatInstantRange("en", microsecondInstant, "2026-10-26T02:38:00Z"),
    ).toBe("25 Oct 2026, 15:38 – 26 Oct 2026, 09:38");
  });

  it("appends the zone label once for business-critical windows", () => {
    expect(
      formatInstantRange("vi", microsecondInstant, "2026-10-25T10:38:00Z", {
        withTimeZone: true,
      }),
    ).toBe("15:38, 25/10/2026 – 17:38 (giờ Việt Nam, UTC+7)");
  });

  it("returns an em dash when either endpoint is invalid", () => {
    expect(formatInstantRange("vi", microsecondInstant, "not-a-date")).toBe(
      "—",
    );
    expect(formatInstantRange("en", null, microsecondInstant)).toBe("—");
  });
});
