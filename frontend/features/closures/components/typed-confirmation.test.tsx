import { fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TypedConfirmation } from "./typed-confirmation";

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(true)} type="button">
        Open closure confirmation
      </button>
      {open ? (
        <TypedConfirmation
          confirmLabel="COMPLETE closure"
          onCancel={() => setOpen(false)}
          onConfirm={() => setOpen(false)}
          phrase="COMPLETE"
          title="Confirm completion command"
        >
          <p>Review the server-authoritative closure command.</p>
        </TypedConfirmation>
      ) : null}
    </>
  );
}

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    },
  });
});

describe("closure typed confirmation", () => {
  it("traps keyboard focus, cancels with Escape and restores the trigger", () => {
    render(<Harness />);
    const trigger = screen.getByRole("button", {
      name: "Open closure confirmation",
    });
    trigger.focus();
    fireEvent.click(trigger);

    const dialog = screen.getByRole("dialog", {
      name: "Confirm completion command",
    });
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    expect(cancel).toHaveFocus();

    fireEvent.keyDown(dialog, { key: "Tab" });
    expect(
      within(dialog).getByLabelText("Type COMPLETE to enable this action"),
    ).toHaveFocus();

    fireEvent(
      dialog,
      new Event("cancel", { bubbles: false, cancelable: true }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
