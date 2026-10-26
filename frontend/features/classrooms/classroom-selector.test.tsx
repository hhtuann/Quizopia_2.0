import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { ClassroomSelector } from "./classroom-selector";
import type { ClassroomApiClient } from "./api/classroom-api-client";
import type { ClassroomSummary } from "./api/classroom-contract.generated";

const classroom: ClassroomSummary = {
  id: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  name: "Networks 12A",
  subject: "Computer Science",
  grade: "12",
  description: null,
  createdAt: "2026-10-26T08:00:00Z",
};
function client() {
  return {
    list: vi.fn<ClassroomApiClient["list"]>(),
    get: vi.fn<ClassroomApiClient["get"]>(),
  };
}
function Choice({
  api,
  initial = "",
  valid,
}: {
  api: ClassroomApiClient;
  initial?: string;
  valid: (value: boolean) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <ClassroomSelector
      client={api}
      value={value}
      onChange={setValue}
      onValidityChange={valid}
    />
  );
}
describe("owned Classroom selector", () => {
  it("never auto-selects, loads more, and selects exactly one with native radios", async () => {
    const api = client();
    const valid = vi.fn();
    api.list
      .mockResolvedValueOnce({
        ok: true,
        value: { items: [classroom], nextCursor: "next" },
      })
      .mockResolvedValueOnce({
        ok: true,
        value: {
          items: [
            {
              ...classroom,
              id: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
              name: "Networks 12B",
            },
          ],
          nextCursor: null,
        },
      });
    render(<Choice api={api} valid={valid} />);
    const first = await screen.findByRole("radio", { name: "Networks 12A" });
    expect(first).not.toBeChecked();
    expect(valid).toHaveBeenLastCalledWith(false);
    fireEvent.click(first);
    await waitFor(() => expect(valid).toHaveBeenLastCalledWith(true));
    expect(first).toBeChecked();
    fireEvent.click(
      screen.getByRole("button", { name: "Load more Classrooms" }),
    );
    const second = await screen.findByRole("radio", { name: "Networks 12B" });
    expect(first).toBeChecked();
    fireEvent.click(second);
    expect(first).not.toBeChecked();
    expect(second).toBeChecked();
    expect(api.list.mock.calls[1]![0]).toBe("next");
    expect(api.get).not.toHaveBeenCalled();
  });
  it("resolves saved selection outside the first page and preserves it", async () => {
    const api = client();
    const valid = vi.fn();
    api.list.mockResolvedValue({
      ok: true,
      value: { items: [], nextCursor: "next" },
    });
    api.get.mockResolvedValue({ ok: true, value: classroom });
    render(<Choice api={api} valid={valid} initial={classroom.id} />);
    expect(
      await screen.findByRole("radio", { name: classroom.name }),
    ).toBeChecked();
    expect(api.get).toHaveBeenCalledWith(classroom.id, expect.any(AbortSignal));
    expect(valid).toHaveBeenLastCalledWith(true);
  });
  it("blocks an inaccessible selection, supports retry, and never substitutes another classroom", async () => {
    const api = client();
    const valid = vi.fn();
    api.list.mockResolvedValue({
      ok: true,
      value: { items: [], nextCursor: null },
    });
    api.get
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "api", status: 404, code: "CLASSROOM_NOT_ACCESSIBLE" },
      })
      .mockResolvedValueOnce({ ok: true, value: classroom });
    render(<Choice api={api} valid={valid} initial={classroom.id} />);
    await screen.findByRole("button", { name: "Retry selected Classroom" });
    expect(valid).toHaveBeenLastCalledWith(false);
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry selected Classroom" }),
    );
    expect(
      await screen.findByRole("radio", { name: classroom.name }),
    ).toBeChecked();
    expect(valid).toHaveBeenLastCalledWith(true);
  });
  it("retries directory errors and aborts an obsolete selected-resource lookup", async () => {
    const api = client();
    const valid = vi.fn();
    api.list
      .mockResolvedValueOnce({
        ok: false,
        error: { kind: "unavailable", reason: "network" },
      })
      .mockResolvedValue({ ok: true, value: { items: [], nextCursor: null } });
    let resolve!: (
      value: Awaited<ReturnType<ClassroomApiClient["get"]>>,
    ) => void;
    api.get.mockImplementation(
      () =>
        new Promise((finish) => {
          resolve = finish;
        }),
    );
    const mounted = render(
      <Choice api={api} valid={valid} initial={classroom.id} />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry Classrooms" }),
    );
    await screen.findByText(/No owned Classrooms yet/);
    mounted.unmount();
    expect(api.get.mock.calls[0]![1]!.aborted).toBe(true);
    await act(async () => resolve({ ok: true, value: classroom }));
    expect(valid).not.toHaveBeenCalledWith(true);
  });
});
