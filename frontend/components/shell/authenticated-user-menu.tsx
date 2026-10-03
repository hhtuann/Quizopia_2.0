"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useAuth } from "../../features/auth/auth-provider";

const workspaceLabels = {
  LEARNING: "Learning",
  TEACHING: "Teaching",
} as const;

export function AuthenticatedUserMenu() {
  const { activeWorkspace, logout, switchWorkspace, user } = useAuth();
  const [open, setOpen] = useState(false);
  const [isSigningOut, setIsSigningOut] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }

    function closeOnOutsidePointer(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    menuRef.current
      ?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')
      ?.focus();
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () =>
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
  }, [open]);

  if (user === null) {
    return null;
  }

  const workspace =
    activeWorkspace === null
      ? "No workspace"
      : workspaceLabels[activeWorkspace];
  const initial = user.username.trim().charAt(0).toLocaleUpperCase() || "U";
  const canSwitchToLearning =
    activeWorkspace === "TEACHING" && user.roles.includes("STUDENT");
  const canSwitchToTeaching =
    activeWorkspace === "LEARNING" && user.roles.includes("TEACHER");
  const needsTeacherRegistration =
    activeWorkspace === "LEARNING" && !user.roles.includes("TEACHER");

  function closeAndRestoreFocus() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      closeAndRestoreFocus();
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") {
      return;
    }
    event.preventDefault();
    const items = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>(
        '[role="menuitem"]:not(:disabled)',
      ),
    );
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const delta = event.key === "ArrowDown" ? 1 : -1;
    const next =
      current < 0 ? 0 : (current + delta + items.length) % items.length;
    items[next]?.focus();
  }

  function switchTo(workspaceToOpen: "LEARNING" | "TEACHING") {
    if (switchWorkspace(workspaceToOpen)) {
      setMessage(null);
      closeAndRestoreFocus();
    }
  }

  async function handleLogout() {
    if (isSigningOut) {
      return;
    }
    setMessage(null);
    setIsSigningOut(true);
    const result = await logout();
    if (!result.ok) {
      setMessage(
        "Sign out could not be completed. Your session is still active.",
      );
      setIsSigningOut(false);
    }
  }

  const menuItemClasses =
    "flex min-h-11 w-full items-center rounded-md px-3 py-2 text-left text-sm font-medium text-foreground-secondary transition-colors hover:bg-surface-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none";

  return (
    <div className="relative sm:ml-auto" ref={rootRef}>
      <button
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`Open user menu for ${user.username}, ${workspace} workspace`}
        className="flex min-h-11 max-w-full items-center gap-3 rounded-lg border border-border bg-surface px-2.5 py-1.5 text-left transition-colors hover:border-border-strong hover:bg-surface-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
        onClick={() => {
          setMessage(null);
          setOpen((current) => !current);
        }}
        ref={triggerRef}
        type="button"
      >
        <span
          aria-label={`${user.username} avatar fallback`}
          className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary"
          role="img"
        >
          {initial}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold text-foreground">
            {user.username}
          </span>
          <span className="block text-xs text-foreground-muted">
            {workspace}
          </span>
        </span>
        <svg
          aria-hidden="true"
          className={`size-4 shrink-0 text-foreground-muted transition-transform ${open ? "rotate-180" : ""}`}
          fill="none"
          viewBox="0 0 24 24"
        >
          <path
            d="m6 9 6 6 6-6"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
          />
        </svg>
      </button>

      {open ? (
        <div
          aria-label="User menu"
          className="absolute right-0 z-50 mt-2 w-[min(20rem,calc(100vw-2rem))] rounded-xl border border-border bg-surface p-2 shadow-card"
          onKeyDown={handleMenuKeyDown}
          ref={menuRef}
          role="menu"
        >
          <div className="border-b border-border px-3 py-2">
            <p className="truncate text-sm font-semibold text-foreground">
              {user.username}
            </p>
            <p className="truncate text-xs text-foreground-muted">
              {user.email}
            </p>
          </div>

          <button
            className={menuItemClasses}
            onClick={() =>
              setMessage(
                "Account settings are unavailable until Identity exposes profile and avatar update APIs.",
              )
            }
            role="menuitem"
            type="button"
          >
            Account settings
            <span className="ml-auto text-xs text-foreground-muted">
              Unavailable
            </span>
          </button>

          {canSwitchToLearning ? (
            <button
              className={menuItemClasses}
              onClick={() => switchTo("LEARNING")}
              role="menuitem"
              type="button"
            >
              Switch to Learning
            </button>
          ) : null}
          {canSwitchToTeaching ? (
            <button
              className={menuItemClasses}
              onClick={() => switchTo("TEACHING")}
              role="menuitem"
              type="button"
            >
              Switch to Teaching
            </button>
          ) : null}
          {needsTeacherRegistration ? (
            <button
              className={menuItemClasses}
              onClick={() =>
                setMessage(
                  "Teacher registration is not available until Identity exposes the accepted self-enablement API.",
                )
              }
              role="menuitem"
              type="button"
            >
              Register as teacher
              <span className="ml-auto text-xs text-foreground-muted">
                Unavailable
              </span>
            </button>
          ) : null}

          <div className="mt-1 border-t border-border pt-1">
            <button
              className={menuItemClasses}
              disabled={isSigningOut}
              onClick={() => void handleLogout()}
              role="menuitem"
              type="button"
            >
              {isSigningOut ? "Signing out…" : "Sign out"}
            </button>
          </div>

          {message ? (
            <p
              className="mx-2 mt-1 rounded-lg bg-surface-muted px-3 py-2 text-xs leading-5 text-foreground-secondary"
              role="status"
            >
              {message}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
