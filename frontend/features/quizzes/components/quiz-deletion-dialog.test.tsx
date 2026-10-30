import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  QuizDeletionAccepted,
  QuizDeletionApiClient,
  QuizDeletionResult,
} from "../api/quiz-deletion-client";
import { QuizDeletionDialog } from "./quiz-deletion-dialog";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const operationId = "1c9d0d7e-5c8a-4b8f-9a55-1d3a6d3a2a11";
beforeEach(() => sessionStorage.clear());

type RequestCall = {
  readonly confirmTitle: string;
  readonly idempotencyKey: string;
};

type RequestOutcome = QuizDeletionResult<QuizDeletionAccepted>;

function accepted(
  status: QuizDeletionAccepted["status"] = "REQUESTED",
): RequestOutcome {
  return { ok: true, value: { operationId, status } };
}

function apiError(code: string, status: number): RequestOutcome {
  return {
    ok: false,
    error: {
      kind: "api-error",
      error: {
        code,
        message: `${code} message`,
        status,
        path: "/api/quizzes/x",
      },
    },
  };
}

function makeClient(outcomes: ReadonlyArray<RequestOutcome>) {
  const calls: RequestCall[] = [];
  const requestDeletion = vi.fn(
    async (
      _quizId: string,
      input: { confirmTitle: string; idempotencyKey: string },
    ): Promise<RequestOutcome> => {
      calls.push(input);
      const next = outcomes[Math.min(calls.length - 1, outcomes.length - 1)];
      return next === undefined ? accepted() : next;
    },
  );
  const client = {
    requestDeletion: (
      quizId: string,
      input: { confirmTitle: string; idempotencyKey: string },
    ) => requestDeletion(quizId, input),
    getDeletionStatus: vi.fn(),
    getLatestDeletionStatus: vi.fn(async () => ({
      ok: false as const,
      error: {
        kind: "api-error" as const,
        error: {
          code: "QUIZ_DELETION_NOT_FOUND",
          message: "Not found",
          status: 404,
          path: "/",
        },
      },
    })),
  } satisfies QuizDeletionApiClient;
  return { calls, client, requestDeletion };
}

function renderDialog(options: {
  readonly client: QuizDeletionApiClient;
  readonly authoritativeTitle?: string | null;
  readonly onAccepted?: (accepted: QuizDeletionAccepted) => void;
  readonly onClose?: () => void;
}) {
  const onAccepted = vi.fn(options.onAccepted);
  const onClose = vi.fn(options.onClose);
  const trigger = document.createElement("button");
  document.body.appendChild(trigger);
  const view = render(
    <StrictMode>
      <QuizDeletionDialog
        authoritativeTitle={
          options.authoritativeTitle === undefined
            ? "Network quiz"
            : options.authoritativeTitle
        }
        client={options.client}
        onAccepted={onAccepted}
        onClose={onClose}
        quizId={quizId}
        triggerRef={{ current: trigger }}
      />
    </StrictMode>,
  );
  return { onClose, onAccepted, trigger, view };
}

describe("QuizDeletionDialog exact-title confirmation", () => {
  it("states the permanent consequences honestly without fabricated counts", () => {
    const { client } = makeClient([]);
    renderDialog({ client });

    expect(
      screen.getByRole("heading", { name: "Delete this quiz permanently?" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "This permanently deletes the quiz, its draft and every published version. Linked assessments, answers, grades and results are permanently deleted when they are eligible; some records can be kept longer only when the law requires it.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Students immediately lose access once revocation completes.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Current title")).toBeInTheDocument();
    expect(screen.getByText("Network quiz")).toBeInTheDocument();
  });

  it("keeps the destructive action disabled while the confirmation is empty", () => {
    const { client } = makeClient([]);
    renderDialog({ client });

    expect(
      screen.getByRole("button", { name: "Delete permanently" }),
    ).toBeDisabled();
    expect(
      screen.queryByText(
        "The title does not match. Type it exactly, including capitalization.",
      ),
    ).not.toBeInTheDocument();
  });

  it("rejects a near-miss title and stays disabled", () => {
    const { client } = makeClient([]);
    renderDialog({ client });

    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network qui" },
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The title does not match. Type it exactly, including capitalization.",
    );
    expect(
      screen.getByRole("button", { name: "Delete permanently" }),
    ).toBeDisabled();
  });

  it("rejects a case-mismatched title and stays disabled", () => {
    const { client } = makeClient([]);
    renderDialog({ client });

    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "network quiz" },
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The title does not match. Type it exactly, including capitalization.",
    );
    expect(
      screen.getByRole("button", { name: "Delete permanently" }),
    ).toBeDisabled();
  });

  it("enables the destructive action for an exact match, tolerating outer whitespace", () => {
    const { client } = makeClient([]);
    renderDialog({ client });

    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "  Network quiz  " },
    });
    expect(
      screen.queryByText(
        "The title does not match. Type it exactly, including capitalization.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Delete permanently" }),
    ).toBeEnabled();
  });

  it.each([null, "", "   "])(
    "requires explicit irreversible confirmation for untitled %s and sends empty title",
    async (authoritativeTitle) => {
      const { client, calls } = makeClient([]);
      renderDialog({ client, authoritativeTitle });

      expect(
        screen.getByText(
          "I understand that permanently deleting this untitled quiz is irreversible.",
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByLabelText("Type the exact title to confirm"),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Delete permanently" }),
      ).toBeDisabled();
      fireEvent.click(screen.getByRole("checkbox"));
      fireEvent.click(
        screen.getByRole("button", { name: "Delete permanently" }),
      );
      await waitFor(() => expect(calls).toHaveLength(1));
      expect(calls[0]?.confirmTitle).toBe("");
    },
  );

  it("preserves the retry key after an uncertain outcome and dialog remount, reconciling first", async () => {
    const { client, calls } = makeClient([
      {
        ok: false,
        error: { kind: "transport-error", error: { kind: "network" } },
      },
      accepted(),
    ]);
    const first = renderDialog({ client });
    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network quiz" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await screen.findByText("Quizopia could not connect. Try again.");
    first.view.unmount();
    renderDialog({ client });
    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network quiz" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(client.getLatestDeletionStatus).toHaveBeenCalledWith(quizId);
    expect(calls[1]).toEqual(calls[0]);
  });

  it("sends the trimmed exact title with a freshly generated key on confirm", async () => {
    const { calls, client } = makeClient([accepted("QUIZ_FENCED")]);
    const { onAccepted } = renderDialog({ client });

    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: " Network quiz " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));

    await waitFor(() => expect(onAccepted).toHaveBeenCalledTimes(1));
    expect(onAccepted).toHaveBeenCalledWith({
      operationId,
      status: "QUIZ_FENCED",
    });
    expect(calls).toEqual([
      { confirmTitle: "Network quiz", idempotencyKey: expect.any(String) },
    ]);
    expect(calls[0]?.idempotencyKey).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  });

  it("retries a failed request with the SAME idempotency key", async () => {
    const { calls, client } = makeClient([
      apiError("TITLE_CONFIRMATION_REQUIRED", 400),
      accepted("REQUESTED"),
    ]);
    const { onAccepted } = renderDialog({ client });
    const confirm = screen.getByRole("button", { name: "Delete permanently" });

    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network quiz" },
    });
    fireEvent.click(confirm);

    expect(
      await screen.findByText(
        "The typed title does not match the current quiz title. Reload the quiz and try again.",
      ),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => expect(onAccepted).toHaveBeenCalledTimes(1));
    expect(calls).toHaveLength(2);
    expect(calls[1]?.idempotencyKey).toBe(calls[0]?.idempotencyKey);
  });

  it("starts a NEW intent with a NEW key after closing and reopening", async () => {
    const { calls, client } = makeClient([
      accepted("REQUESTED"),
      accepted("REQUESTED"),
    ]);
    // The dialog is mounted only while open: closing (parent conditional
    // render) unmounts it, reopening mounts a fresh intent with a fresh key.
    const first = renderDialog({ client });
    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network quiz" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => expect(calls).toHaveLength(1));

    first.view.unmount();
    renderDialog({ client });
    fireEvent.change(screen.getByLabelText("Type the exact title to confirm"), {
      target: { value: "Network quiz" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]?.idempotencyKey).not.toBe(calls[0]?.idempotencyKey);
  });

  it("maps 409 DELETION_CONFLICT, QUIZ_DELETION_PENDING and 404 honestly", async () => {
    const cases: ReadonlyArray<{
      readonly code: string;
      readonly status: number;
      readonly copy: string;
    }> = [
      {
        code: "DELETION_CONFLICT",
        status: 409,
        copy: "A deletion was already requested for this quiz. Reload to see its current status.",
      },
      {
        code: "QUIZ_DELETION_PENDING",
        status: 409,
        copy: "This quiz is already being deleted.",
      },
      {
        code: "QUIZ_NOT_FOUND",
        status: 404,
        copy: "This quiz no longer exists. Reload your quiz library.",
      },
    ];
    for (const testCase of cases) {
      const { client } = makeClient([apiError(testCase.code, testCase.status)]);
      const { view } = renderDialog({ client });
      fireEvent.change(
        screen.getByLabelText("Type the exact title to confirm"),
        { target: { value: "Network quiz" } },
      );
      fireEvent.click(
        screen.getByRole("button", { name: "Delete permanently" }),
      );
      expect(await screen.findByText(testCase.copy)).toBeInTheDocument();
      view.unmount();
    }
  });

  it("closes on Escape while idle and returns focus to its trigger", () => {
    const { client } = makeClient([]);
    const { onClose, trigger } = renderDialog({ client });

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    // Focus restoration happens in the open-effect cleanup.
    expect(document.activeElement).toBe(trigger);
  });

  it("traps Tab focus inside the dialog", () => {
    const { client } = makeClient([]);
    renderDialog({ client });
    const dialog = screen.getByRole("dialog");

    const focusable = dialog.querySelectorAll(
      "button:not([disabled]), input:not([disabled])",
    );
    const last = focusable.item(focusable.length - 1) as HTMLElement;
    const first = focusable.item(0) as HTMLElement;
    last.focus();
    fireEvent.keyDown(dialog, { key: "Tab" });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(dialog, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);
  });
});
