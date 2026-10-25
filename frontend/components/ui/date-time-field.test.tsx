import { render, screen, fireEvent } from "@testing-library/react";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import {
  DateTimeField,
  instantToLocalParts,
  localPartsToInstant,
  timezoneLabel,
} from "./date-time-field";

// DST tests require a timezone with transitions. Node honors a runtime
// process.env.TZ change on POSIX (CI); Windows ignores it. Detect whether the
// America/New_York override actually took effect and skip DST-specific
// assertions when it did not (e.g. local Windows development).
const previousTz = process.env.TZ;
let dstTimezoneActive = false;
try {
  process.env.TZ = "America/New_York";
  const januaryOffset = new Date("2026-01-15T12:00:00Z").getTimezoneOffset();
  const julyOffset = new Date("2026-07-15T12:00:00Z").getTimezoneOffset();
  dstTimezoneActive = januaryOffset !== julyOffset;
} catch {
  dstTimezoneActive = false;
}
afterAll(() => {
  if (previousTz === undefined) delete process.env.TZ;
  else process.env.TZ = previousTz;
});

describe("instantToLocalParts", () => {
  it("converts UTC instant to local date/time parts", () => {
    // Use a fixed UTC instant; the local parts depend on the test runner timezone.
    const result = instantToLocalParts("2026-10-16T08:00:00Z");
    expect(result).not.toBeNull();
    expect(result!.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(result!.time).toMatch(/^\d{2}:\d{2}$/);
  });

  it("returns null for empty or invalid input", () => {
    expect(instantToLocalParts("")).toBeNull();
    expect(instantToLocalParts("not-a-date")).toBeNull();
    expect(instantToLocalParts("2026-13-45T99:99:99Z")).toBeNull();
  });
});

describe("localPartsToInstant", () => {
  it("converts valid local parts to a UTC instant", () => {
    const result = localPartsToInstant("2026-10-16", "08:00");
    expect(result).not.toBeNull();
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });

  it("returns null for incomplete input", () => {
    expect(localPartsToInstant("", "08:00")).toBeNull();
    expect(localPartsToInstant("2026-10-16", "")).toBeNull();
    expect(localPartsToInstant("", "")).toBeNull();
  });

  it("round-trips correctly through instantToLocalParts", () => {
    const utc = "2026-10-16T08:00:00Z";
    const parts = instantToLocalParts(utc);
    expect(parts).not.toBeNull();
    const back = localPartsToInstant(parts!.date, parts!.time);
    expect(back).not.toBeNull();
    expect(new Date(back!).getTime()).toBe(new Date(utc).getTime());
  });

  it("handles midnight boundary without date shift", () => {
    // Midnight local in any timezone should produce a valid instant the same local date
    const result = localPartsToInstant("2026-10-16", "00:00");
    expect(result).not.toBeNull();
    const back = instantToLocalParts(result!);
    expect(back).not.toBeNull();
    expect(back!.date).toBe("2026-10-16");
    expect(back!.time).toBe("00:00");
  });

  it.skipIf(!dstTimezoneActive)(
    "rejects nonexistent spring-forward local time instead of normalizing",
    () => {
      // 2026-03-08 02:30 America/New_York does not exist (02:00 -> 03:00).
      const result = localPartsToInstant("2026-03-08", "02:30");
      expect(result).toBeNull();
      // A time after the gap is valid and round-trips.
      expect(localPartsToInstant("2026-03-08", "03:30")).not.toBeNull();
    },
  );

  it.skipIf(!dstTimezoneActive)(
    "accepts ambiguous fall-back local time resolving to a valid occurrence",
    () => {
      // 2026-11-01 01:30 America/New_York occurs twice (EDT then EST).
      const result = localPartsToInstant("2026-11-01", "01:30");
      expect(result).not.toBeNull();
      const epoch = new Date(result!).getTime();
      // The engine must resolve to one of the two legal occurrences.
      expect([
        Date.parse("2026-11-01T05:30:00Z"),
        Date.parse("2026-11-01T06:30:00Z"),
      ]).toContain(epoch);
      // Round-trip preserves the selected wall clock.
      const back = instantToLocalParts(result!);
      expect(back).toMatchObject({ date: "2026-11-01", time: "01:30" });
    },
  );
});

describe("timezoneLabel", () => {
  it("returns a non-empty label with UTC offset for the current date", () => {
    const label = timezoneLabel();
    expect(label.length).toBeGreaterThan(0);
    expect(label).toMatch(/UTC[+-]\d{2}:\d{2}/);
  });

  it("formats an offset for a provided date", () => {
    expect(timezoneLabel("2026-10-16")).toMatch(/UTC[+-]\d{2}:\d{2}/);
    expect(timezoneLabel("2026-10-16")).not.toBeNull();
  });

  it("falls back gracefully for an invalid date", () => {
    expect(timezoneLabel("not-a-date")).toBe("Local time");
  });

  it.skipIf(!dstTimezoneActive)(
    "computes the offset for the selected date, not today (DST-aware)",
    () => {
      const january = timezoneLabel("2026-01-15"); // EST UTC-5
      const july = timezoneLabel("2026-07-15"); // EDT UTC-4
      expect(january).toContain("-05:00");
      expect(july).toContain("-04:00");
    },
  );

  it.skipIf(!dstTimezoneActive)(
    "offset follows the exact selected time on the spring-forward day",
    () => {
      // 2026-03-08 in America/New_York: 02:00 jumps to 03:00 (EST -> EDT).
      expect(timezoneLabel("2026-03-08", "01:30")).toContain("-05:00");
      expect(timezoneLabel("2026-03-08", "03:30")).toContain("-04:00");
    },
  );

  it.skipIf(!dstTimezoneActive)(
    "ambiguous fall-back time shows the first-occurrence offset",
    () => {
      // 2026-11-01 01:30 America/New_York occurs twice: EDT (UTC-4) then
      // EST (UTC-5). The accepted policy resolves to the first occurrence.
      expect(timezoneLabel("2026-11-01", "01:30")).toContain("-04:00");
    },
  );

  it.skipIf(!dstTimezoneActive)(
    "nonexistent gap time falls back to the date's noon offset, not the normalized one",
    () => {
      // 02:30 on 2026-03-08 does not exist; noon that day is EDT (UTC-4).
      expect(timezoneLabel("2026-03-08", "02:30")).toContain("-04:00");
    },
  );

  it("Asia/Ho_Chi_Minh is fixed UTC+07:00 for the exact selected time", () => {
    process.env.TZ = "Asia/Ho_Chi_Minh";
    const offsetIsPlus7 =
      new Date("2026-06-15T12:00:00Z").getTimezoneOffset() === -420;
    try {
      if (!offsetIsPlus7) return; // TZ override inactive (non-POSIX) and not UTC+7
      expect(timezoneLabel("2026-10-16", "08:00")).toContain("+07:00");
      expect(timezoneLabel("2026-03-08", "01:30")).toContain("+07:00");
    } finally {
      process.env.TZ = "America/New_York";
    }
  });
});

describe("DateTimeField", () => {
  it("renders label, date input, time input, and timezone hint", () => {
    render(
      <DateTimeField
        label="Participation start"
        onChange={() => {}}
        value=""
      />,
    );
    expect(screen.getByLabelText("Participation start")).toBeInTheDocument();
    expect(
      screen.getByLabelText("Participation start time"),
    ).toBeInTheDocument();
    expect(screen.getByText(/Timezone:/)).toBeInTheDocument();
  });

  it("calls onChange with a UTC instant when both date and time are set", () => {
    const onChange = vi.fn();
    render(<DateTimeField label="Start" onChange={onChange} value="" />);

    fireEvent.change(screen.getByLabelText("Start"), {
      target: { value: "2026-10-16" },
    });
    // Date only: onChange with empty string (time not yet set)
    expect(onChange).toHaveBeenLastCalledWith("");

    fireEvent.change(screen.getByLabelText("Start time"), {
      target: { value: "08:00" },
    });
    // Both set: onChange should receive a valid UTC instant
    expect(onChange).toHaveBeenLastCalledWith(
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    );
  });

  it("displays error message when provided", () => {
    render(
      <DateTimeField
        error="Start must be before end."
        label="Start"
        onChange={() => {}}
        value=""
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Start must be before end.",
    );
  });

  it("displays initial UTC value in local date and time inputs", () => {
    const initial = "2026-10-16T08:00:00Z";
    const parts = instantToLocalParts(initial);
    render(<DateTimeField label="Start" onChange={() => {}} value={initial} />);
    const dateInput = screen.getByLabelText("Start") as HTMLInputElement;
    const timeInput = screen.getByLabelText("Start time") as HTMLInputElement;
    expect(dateInput.value).toBe(parts!.date);
    expect(timeInput.value).toBe(parts!.time);
  });

  it("does not emit onChange on mount, preserving the caller's UTC instant", () => {
    const onChange = vi.fn();
    const initial = "2026-10-16T08:00:00.123456Z"; // microsecond precision
    render(<DateTimeField label="Start" onChange={onChange} value={initial} />);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("disables both inputs when disabled", () => {
    render(
      <DateTimeField disabled label="Start" onChange={() => {}} value="" />,
    );
    expect(screen.getByLabelText("Start")).toBeDisabled();
    expect(screen.getByLabelText("Start time")).toBeDisabled();
  });

  it.skipIf(!dstTimezoneActive)(
    "emits empty string for a nonexistent spring-forward local time",
    () => {
      const onChange = vi.fn();
      render(<DateTimeField label="Start" onChange={onChange} value="" />);
      fireEvent.change(screen.getByLabelText("Start"), {
        target: { value: "2026-03-08" },
      });
      fireEvent.change(screen.getByLabelText("Start time"), {
        target: { value: "02:30" },
      });
      expect(onChange).toHaveBeenLastCalledWith("");
    },
  );
});
