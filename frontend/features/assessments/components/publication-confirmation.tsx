"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import { Button } from "../../../components/ui/button";

export function PublicationConfirmation({
  title,
  children,
  confirmLabel,
  destructive = false,
  onConfirm,
  onCancel,
}: {
  readonly title: string;
  readonly children: ReactNode;
  readonly confirmLabel: string;
  readonly destructive?: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    const previousFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    const element = dialog.current;
    element?.showModal();
    cancel.current?.focus();
    return () => {
      element?.close();
      previousFocus?.focus();
    };
  }, []);
  return (
    <dialog
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-description`}
      className="fixed inset-0 m-auto max-h-[90dvh] w-[calc(100%-2rem)] max-w-2xl overflow-y-auto rounded-xl border border-border bg-surface p-5 text-foreground shadow-card backdrop:bg-foreground/40 sm:p-7"
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      onKeyDown={(event) => {
        if (event.key !== "Tab" || dialog.current === null) return;
        const controls = Array.from(
          dialog.current.querySelectorAll<HTMLElement>(
            'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
          ),
        ).filter((element) => !element.hidden);
        const first = controls[0];
        const last = controls.at(-1);
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }}
      ref={dialog}
    >
      <h2 className="font-heading text-2xl font-bold" id={`${id}-title`}>
        {title}
      </h2>
      <div
        className="mt-4 space-y-4 text-sm leading-6 text-foreground-secondary"
        id={`${id}-description`}
      >
        {children}
      </div>
      <div className="mt-6 flex flex-wrap justify-end gap-3">
        <Button onClick={onCancel} ref={cancel} variant="secondary">
          Cancel
        </Button>
        <Button
          onClick={onConfirm}
          variant={destructive ? "danger" : "primary"}
        >
          {confirmLabel}
        </Button>
      </div>
    </dialog>
  );
}
