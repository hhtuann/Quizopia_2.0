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
import { QuizopiaLogo } from "../brand/quizopia-logo";
import { LoadingIndicator } from "../ui/loading-indicator";

const workspaceNames: Record<Workspace, string> = {
  LEARNING: "Learning",
  TEACHING: "Teaching",
};

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
 * cleared instead of blocking the screen forever.
 */
const TRANSITION_SAFETY_LIMIT_MS = 10_000;

export function WorkspaceTransitionProvider({
  children,
}: {
  readonly children: ReactNode;
}) {
  const [destination, setDestination] = useState<Workspace | null>(null);
  const destinationRef = useRef<Workspace | null>(null);

  const endTransition = useCallback(() => {
    setDestination((current) => {
      if (current === null) return null;
      destinationRef.current = null;
      // Focus the workspace home landmark once the transition settles so
      // keyboard users land on the new context, not on a stale control.
      window.setTimeout(() => {
        document.getElementById("main-content")?.focus();
      }, 0);
      return null;
    });
  }, []);

  const startTransition = useCallback((next: Workspace) => {
    if (destinationRef.current !== null) return false;
    destinationRef.current = next;
    setDestination(next);
    return true;
  }, []);

  const notifyWorkspaceHomeRendered = useCallback(
    (workspace: Workspace | null) => {
      if (workspace !== null && workspace === destinationRef.current) {
        endTransition();
      }
    },
    [endTransition],
  );

  useEffect(() => {
    if (destination === null) return;
    const timer = window.setTimeout(endTransition, TRANSITION_SAFETY_LIMIT_MS);
    return () => window.clearTimeout(timer);
  }, [destination, endTransition]);

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
      <LoadingIndicator label="Changing your workspace" size="medium" />
      <div aria-live="polite" className="space-y-1.5" role="status">
        <p className="font-heading text-xl font-bold text-foreground sm:text-2xl">
          Changing your workspace…
        </p>
        <p className="text-sm text-foreground-secondary">
          Switching to {workspaceNames[destination]} workspace
        </p>
      </div>
    </div>
  );
}
