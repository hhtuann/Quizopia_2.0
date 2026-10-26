"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "../../lib/i18n/provider";
import { useAuth } from "../../features/auth/auth-provider";
import { useTeacherEnablement } from "../../features/auth/hooks/use-teacher-enablement";
import { confirmUnsavedWorkLeaving } from "../../features/attempts/hooks/use-unsaved-navigation-guard";
import { useWorkspaceTransition } from "./workspace-transition-overlay";
import { BookOpenIcon, CheckIcon } from "../ui/icons";
import type { Locale } from "../../lib/i18n/core";

const workspaceLabels = {
  LEARNING: "Learning",
  TEACHING: "Teaching",
} as const;

// Locale-invariant by design (mirrors the standalone switcher): both language
// names and the section label stay identical in either locale so the control
// is findable regardless of the current UI language.
const languageSectionLabel = "Language / Ngôn ngữ";

const languageOptions: readonly { locale: Locale; label: string }[] = [
  { locale: "vi", label: "Tiếng Việt" },
  { locale: "en", label: "English" },
];

export function AuthenticatedUserMenu() {
  const { t, localize, locale, setLocale } = useI18n();
  const router = useRouter();
  const { activeWorkspace, logout, switchWorkspace, user } = useAuth();
  const {
    clearNotice,
    isPending: isEnablingTeacher,
    notice,
    requestTeacherEnablement,
  } = useTeacherEnablement();
  const { isTransitioning, startTransition } = useWorkspaceTransition();
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
      ? t("No workspace")
      : t(workspaceLabels[activeWorkspace]);
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
    // Arrow keys traverse every focusable menu entry, including the
    // menuitemradio language options, in rendered order.
    const items = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>(
        '[role="menuitem"]:not(:disabled), [role="menuitemradio"]:not(:disabled)',
      ),
    );
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const delta = event.key === "ArrowDown" ? 1 : -1;
    const next =
      current < 0 ? 0 : (current + delta + items.length) % items.length;
    items[next]?.focus();
  }

  function switchTo(workspaceToOpen: "LEARNING" | "TEACHING") {
    // One transition at a time: repeated switch clicks during an in-flight
    // transition must not duplicate the overlay or navigation.
    if (isTransitioning) return;
    // A workspace switch navigates away from the current screen. Screens with
    // unsaved work (Quiz drafts, pending assessment forms, active attempts)
    // must be able to cancel; cancelling keeps workspace state and URL intact.
    if (!confirmUnsavedWorkLeaving()) return;
    if (switchWorkspace(workspaceToOpen)) {
      // Cover the old workspace immediately; the overlay settles when the
      // destination workspace home reports that it has rendered.
      startTransition(workspaceToOpen);
      setMessage(null);
      clearNotice();
      setOpen(false);
      // The previous workspace's URL is no longer meaningful; land on the
      // workspace home instead of leaving a Teaching screen open under a
      // Learning workspace (and vice versa). Client navigation only: roles,
      // tokens and refresh state are untouched.
      router.replace("/app");
    }
  }

  async function handleLogout() {
    if (isSigningOut) {
      return;
    }
    setMessage(null);
    clearNotice();
    setIsSigningOut(true);
    const result = await logout();
    if (result.ok) {
      setOpen(false);
      router.replace("/login");
    } else {
      setMessage(
        "Sign out could not be completed. Your session is still active.",
      );
      setIsSigningOut(false);
    }
  }

  const menuItemClasses =
    "flex min-h-11 w-full items-center rounded-lg px-3 py-2 text-left text-sm font-medium text-foreground-secondary transition-colors hover:bg-primary/5 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus motion-reduce:transition-none";

  return (
    <div className="relative sm:ml-auto" ref={rootRef}>
      <button
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={t("Open user menu for {username}, {workspace} workspace", {
          username: user.username,
          workspace,
        })}
        className="flex min-h-11 max-w-full items-center gap-3 rounded-full border border-border bg-surface py-1 pl-1 pr-3 text-left shadow-sm transition-colors hover:border-primary/30 hover:bg-primary/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 motion-reduce:transition-none"
        onClick={() => {
          setMessage(null);
          clearNotice();
          setOpen((current) => !current);
        }}
        ref={triggerRef}
        type="button"
      >
        <span
          aria-label={t("{username} avatar fallback", {
            username: user.username,
          })}
          className="brand-gradient flex size-9 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white"
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
          aria-label={t("User menu")}
          className="absolute right-0 z-50 mt-2 w-[min(20rem,calc(100vw-2rem))] rounded-xl border border-border bg-surface p-2 shadow-card-hover"
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
            onClick={() => {
              clearNotice();
              setMessage(
                "Account settings are unavailable until Identity exposes profile and avatar update APIs.",
              );
            }}
            role="menuitem"
            type="button"
          >
            {t("Account settings")}
            <span className="ml-auto text-xs text-foreground-muted">
              {t("Unavailable")}
            </span>
          </button>

          {canSwitchToLearning ? (
            <button
              className={menuItemClasses}
              onClick={() => switchTo("LEARNING")}
              role="menuitem"
              type="button"
            >
              {t("Switch to Learning")}
            </button>
          ) : null}
          {canSwitchToTeaching || needsTeacherRegistration ? (
            <button
              aria-busy={isEnablingTeacher || undefined}
              className={menuItemClasses}
              disabled={isEnablingTeacher}
              onClick={() =>
                canSwitchToTeaching
                  ? switchTo("TEACHING")
                  : void requestTeacherEnablement()
              }
              role="menuitem"
              type="button"
            >
              {canSwitchToTeaching
                ? t("Switch to Teaching")
                : isEnablingTeacher
                  ? t("Registering as teacher...")
                  : t("Register as teacher")}
            </button>
          ) : null}

          <div
            aria-labelledby="user-menu-language-label"
            className="mt-1 border-t border-border pt-1"
            role="group"
          >
            <p
              className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-foreground-muted"
              id="user-menu-language-label"
            >
              {languageSectionLabel}
            </p>
            {languageOptions.map((option) => {
              const isCurrent = option.locale === locale;
              return (
                <button
                  aria-checked={isCurrent}
                  className={menuItemClasses}
                  key={option.locale}
                  lang={option.locale}
                  onClick={() => setLocale(option.locale)}
                  role="menuitemradio"
                  type="button"
                >
                  {option.label}
                  {isCurrent ? (
                    <CheckIcon
                      aria-hidden="true"
                      className="ml-auto size-4 shrink-0 text-primary"
                    />
                  ) : null}
                </button>
              );
            })}
          </div>

          <div className="mt-1 border-t border-border pt-1">
            <button
              className={menuItemClasses}
              disabled={isSigningOut}
              onClick={() => void handleLogout()}
              role="menuitem"
              type="button"
            >
              {isSigningOut ? t("Signing out…") : t("Sign out")}
            </button>
          </div>

          {message || notice ? (
            <p
              className="mx-2 mt-1 rounded-lg bg-surface-muted px-3 py-2 text-xs leading-5 text-foreground-secondary"
              role={notice?.kind === "error" ? "alert" : "status"}
            >
              {localize(message ?? notice?.message ?? "")}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
