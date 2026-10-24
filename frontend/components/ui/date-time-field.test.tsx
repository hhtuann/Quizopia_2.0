import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  DateTimeField,
  instantToLocalParts,
  localPartsToInstant,
  timezoneLabel,
} from "./date-time-field";

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
});

describe("timezoneLabel", () => {
  it("returns a non-empty label with UTC offset", () => {
    const label = timezoneLabel();
    expect(label.length).toBeGreaterThan(0);
    expect(label).toMatch(/UTC[+-]\d{2}:\d{2}/);
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

  it("disables both inputs when disabled", () => {
    render(
      <DateTimeField disabled label="Start" onChange={() => {}} value="" />,
    );
    expect(screen.getByLabelText("Start")).toBeDisabled();
    expect(screen.getByLabelText("Start time")).toBeDisabled();
  });
});
