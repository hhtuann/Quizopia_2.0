"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { Workspace } from "../../features/auth/model/workspace";
import { useI18n } from "../../lib/i18n/provider";
import { QuizopiaLogo } from "../brand/quizopia-logo";
import { LoadingIndicator } from "../ui/loading-indicator";

const workspaceNames = {
  LEARNING: "Learning",
  TEACHING: "Teaching",
} as const;

export interface WorkspaceTransitionApi {
  readonly isTransitioning: boolean;
  readonly destination: Workspace | null;
  /**
   * Starts a transition to the destination workspace. Returns false when a
   * transition is already in flight, so repeated switch attempts cannot
   * duplicate the overlay.
   */
  readonly startTransition: (destination: Workspace) => boolean;
  /**
   * Called by the application workspace home when it has actually rendered
   * content for the given workspace. Ends the transition when it matches the
   * pending destination.
   */
  readonly notifyWorkspaceHomeRendered: (workspace: Workspace | null) => void;
}

/**
 * Transition state is presentation-only and deliberately separate from the
 * authoritative role/session state in AuthProvider: it never mutates roles,
 * tokens or refresh behavior. The default context is inert so components
 * remain testable without the provider; the app always mounts it via
 * AppProviders.
 */
const inertTransition: WorkspaceTransitionApi = {
  isTransitioning: false,
  destination: null,
  startTransition: () => false,
  notifyWorkspaceHomeRendered: () => {},
};

const WorkspaceTransitionContext =
  createContext<WorkspaceTransitionApi>(inertTransition);

export function useWorkspaceTransition(): WorkspaceTransitionApi {
  return useContext(WorkspaceTransitionContext);
}

/**
 * Failure recovery, not an artificial loading delay: if the destination home
 * never reports readiness (for example a failed navigation), the overlay is
 * cleared instead of blocking the screen forever. The safety limit includes
 * the minimum display window.
 */
const TRANSITION_SAFETY_LIMIT_MS = 10_000;

/**
 * The overlay stays on screen at least this long so the transition reads as a
 * deliberate, calm change of context instead of a flash. Dismissal requires
 * BOTH the minimum duration AND destination-home readiness; the safety limit
 * above remains the only path that can dismiss without readiness.
 */
const MINIMUM_DISPLAY_MS = 2_500;

export function WorkspaceTransitionProvider({
  children,
}: {
  readonly children: ReactNode;
}) {
  const [destination, setDestination] = useState<Workspace | null>(null);
  const destinationRef = useRef<Workspace | null>(null);
  const destinationReadyRef = useRef(false);
  const minDisplayTimerRef = useRef<number | null>(null);
  const safetyTimerRef = useRef<number | null>(null);
  const focusTimerRef = useRef<number | null>(null);

  const clearTimers = useCallback(() => {
    for (const timer of [minDisplayTimerRef, safetyTimerRef, focusTimerRef]) {
      if (timer.current !== null) {
        window.clearTimeout(timer.current);
        timer.current = null;
      }
    }
  }, []);

  const endTransition = useCallback(() => {
    clearTimers();
    destinationReadyRef.current = false;
    setDestination((current) => {
      if (current === null) return null;
      destinationRef.current = null;
      // Focus the workspace home landmark once the transition settles so
      // keyboard users land on the new context, not on a stale control.
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null;
        document.getElementById("main-content")?.focus();
      }, 0);
      return null;
    });
  }, [clearTimers]);

  const startTransition = useCallback(
    (next: Workspace) => {
      if (destinationRef.current !== null) return false;
      destinationRef.current = next;
      destinationReadyRef.current = false;
      setDestination(next);
      // Minimum display window: when it elapses, dismiss only if the
      // destination home already reported readiness.
      minDisplayTimerRef.current = window.setTimeout(() => {
        minDisplayTimerRef.current = null;
        if (destinationReadyRef.current) endTransition();
      }, MINIMUM_DISPLAY_MS);
      // Stuck-navigation safety recovery (runs regardless of readiness).
      safetyTimerRef.current = window.setTimeout(() => {
        safetyTimerRef.current = null;
        endTransition();
      }, TRANSITION_SAFETY_LIMIT_MS);
      return true;
    },
    [endTransition],
  );

  const notifyWorkspaceHomeRendered = useCallback(
    (workspace: Workspace | null) => {
      if (
        workspace === null ||
        workspace !== destinationRef.current ||
        destinationRef.current === null
      ) {
        return;
      }
      // Readiness alone never dismisses early: only when the minimum display
      // window has already elapsed (its timer fired and cleaned itself up).
      destinationReadyRef.current = true;
      if (minDisplayTimerRef.current === null) endTransition();
    },
    [endTransition],
  );

  useEffect(() => clearTimers, [clearTimers]);

  const api = useMemo<WorkspaceTransitionApi>(
    () => ({
      isTransitioning: destination !== null,
      destination,
      startTransition,
      notifyWorkspaceHomeRendered,
    }),
    [destination, startTransition, notifyWorkspaceHomeRendered],
  );

  return (
    <WorkspaceTransitionContext.Provider value={api}>
      {children}
      {destination !== null ? (
        <WorkspaceTransitionOverlay destination={destination} />
      ) : null}
    </WorkspaceTransitionContext.Provider>
  );
}

/**
 * Full-screen, opaque Corporate Trust transition cover shown between the
 * moment a workspace switch is accepted and the moment the destination
 * workspace home has actually rendered. The old workspace screen is never
 * visible as if it were already the destination, and the underlying page is
 * not interactive (opaque cover blocks pointer input; Tab is held on the
 * status region). The animated indicator respects prefers-reduced-motion via
 * the shared LoadingIndicator's motion-reduce behavior.
 */
function WorkspaceTransitionOverlay({
  destination,
}: {
  readonly destination: Workspace;
}) {
  const { t } = useI18n();
  const regionRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    regionRef.current?.focus();
  }, []);

  return (
    <div
      className="fixed inset-0 z-[80] flex flex-col items-center justify-center gap-6 bg-background px-6 text-center"
      onKeyDown={(event) => {
        // Keep keyboard users on the transition status while the page behind
        // the opaque cover is mid-navigation.
        if (event.key === "Tab") event.preventDefault();
      }}
      ref={regionRef}
      tabIndex={-1}
    >
      <QuizopiaLogo markClassName="size-12 rounded-xl" />
      <LoadingIndicator label={t("Changing your workspace")} size="medium" />
      <div aria-live="polite" className="space-y-1.5" role="status">
        <p className="font-heading text-xl font-bold text-foreground sm:text-2xl">
          {t("Changing your workspace…")}
        </p>
        <p className="text-sm text-foreground-secondary">
          {t("Switching to {workspace} workspace", {
            workspace: t(workspaceNames[destination]),
          })}
        </p>
      </div>
    </div>
  );
}
