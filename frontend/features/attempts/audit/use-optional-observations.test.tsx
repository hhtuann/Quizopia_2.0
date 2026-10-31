import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { useOptionalObservations } from "./use-optional-observations";

const fixture = vi.hoisted(() => ({
  executor: {},
  choice: vi.fn(),
  send: vi.fn(),
  generation: () => 1,
}));
vi.mock("../../auth/auth-provider", () => ({
  useAuth: () => ({
    authenticatedRequests: fixture.executor,
    getSessionGeneration: fixture.generation,
  }),
}));
vi.mock("./observation-client", () => ({ observationClient: () => fixture }));
const streamId = "00000000-0000-4000-8000-000000000001";
beforeEach(() => {
  fixture.choice.mockReset();
  fixture.send.mockReset().mockResolvedValue(200);
  let current = false;
  fixture.choice.mockImplementation(async (enabled?: boolean) => {
    if (enabled !== undefined) current = enabled;
    return {
      available: true,
      enabled: current,
      streamId: current ? streamId : null,
      noticeVersion: "ACADEMIC_DEMO_V1",
      decidedAt: null,
    };
  });
});
function Workspace({ active = true }: { readonly active?: boolean }) {
  const history = useOptionalObservations(streamId, streamId, active, 1);
  return (
    <>
      {history.content}
      <button
        onClick={() =>
          history.queue.answer(
            1,
            { type: "SINGLE_CHOICE", optionLabel: null },
            { type: "SINGLE_CHOICE", optionLabel: "A" },
          )
        }
      >
        Select A
      </button>
      <button>Submit assessment</button>
    </>
  );
}
it("shows an explicit fictional-data notice and never collects on mount or refusal", async () => {
  render(<Workspace />);
  expect(
    await screen.findByText("Optional history is off."),
  ).toBeInTheDocument();
  expect(screen.getByText(/Fictional demo accounts only/)).toBeInTheDocument();
  fireEvent.click(screen.getByText("Select A"));
  expect(fixture.send).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("Continue without history"));
  await waitFor(() =>
    expect(fixture.choice).toHaveBeenCalledWith(false, expect.any(AbortSignal)),
  );
  expect(screen.getByText("Submit assessment")).toBeEnabled();
  expect(fixture.send).not.toHaveBeenCalled();
});
it("suspends during reconciliation and resumes only after revalidating the same opted-in stream", async () => {
  const mounted = render(<Workspace />);
  fireEvent.click(await screen.findByText("Enable optional history"));
  await screen.findByText("Optional history is on for this session.");
  await waitFor(() => expect(fixture.send).toHaveBeenCalledTimes(1));
  mounted.rerender(<Workspace active={false} />);
  fireEvent.click(screen.getByText("Select A"));
  expect(fixture.send).toHaveBeenCalledTimes(1);
  mounted.rerender(<Workspace />);
  await waitFor(() => expect(fixture.send).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByText("Select A"));
  await waitFor(() => expect(fixture.send).toHaveBeenCalledTimes(3));
  expect(
    fixture.choice.mock.calls.filter((args) => args[0] === true),
  ).toHaveLength(1);
});
it("enables only on affirmative choice and stops immediately on withdrawal", async () => {
  render(<Workspace />);
  fireEvent.click(await screen.findByText("Enable optional history"));
  await screen.findByText("Optional history is on for this session.");
  fireEvent.click(screen.getByText("Select A"));
  await waitFor(() => expect(fixture.send).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByText("Stop optional history"));
  await screen.findByText("Optional history is off.");
  fireEvent.click(screen.getByText("Select A"));
  expect(fixture.send).toHaveBeenCalledTimes(2);
  expect(screen.getByText("Submit assessment")).toBeEnabled();
});
