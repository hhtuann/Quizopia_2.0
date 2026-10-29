"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  type ReactNode,
} from "react";
import { useAuth } from "../../auth/auth-provider";

interface AttemptEphemeralContextValue {
  readonly getPublicLocator: (publicationId: string) => string | null;
  readonly rememberPublicLocator: (
    publicationId: string,
    locator: string,
  ) => void;
  /**
   * In-memory publication-title memory, keyed by publicationId and bound to
   * the same session identity as locator memory. The title is captured from
   * the student's own authorized entry preview (the smallest existing-source
   * integration; AttemptView carries no title) and never persists to browser
   * storage, so a hard reload of a deep-linked attempt falls back honestly.
   */
  readonly getPublicationTitle: (publicationId: string) => string | null;
  readonly rememberPublicationTitle: (
    publicationId: string,
    title: string,
  ) => void;
}

const AttemptEphemeralContext =
  createContext<AttemptEphemeralContextValue | null>(null);

export function AttemptEphemeralProvider({
  children,
}: {
  children: ReactNode;
}) {
  const { getSessionGeneration, user } = useAuth();
  const identity = `${user?.id ?? "anonymous"}:${getSessionGeneration() ?? "none"}`;
  const locators = useRef(
    new Map<string, { readonly identity: string; readonly locator: string }>(),
  );

  useEffect(() => {
    locators.current.clear();
  }, [identity]);

  const getPublicLocator = useCallback(
    (publicationId: string) => {
      const entry = locators.current.get(publicationId);
      return entry?.identity === identity ? entry.locator : null;
    },
    [identity],
  );
  const rememberPublicLocator = useCallback(
    (publicationId: string, locator: string) => {
      locators.current.set(publicationId, { identity, locator });
    },
    [identity],
  );
  const titles = useRef(
    new Map<string, { readonly identity: string; readonly title: string }>(),
  );
  useEffect(() => {
    titles.current.clear();
  }, [identity]);
  const getPublicationTitle = useCallback(
    (publicationId: string) => {
      const entry = titles.current.get(publicationId);
      return entry?.identity === identity ? entry.title : null;
    },
    [identity],
  );
  const rememberPublicationTitle = useCallback(
    (publicationId: string, title: string) => {
      titles.current.set(publicationId, { identity, title });
    },
    [identity],
  );
  const value = useMemo(
    () => ({
      getPublicLocator,
      rememberPublicLocator,
      getPublicationTitle,
      rememberPublicationTitle,
    }),
    [
      getPublicLocator,
      rememberPublicLocator,
      getPublicationTitle,
      rememberPublicationTitle,
    ],
  );

  return (
    <AttemptEphemeralContext.Provider value={value}>
      {children}
    </AttemptEphemeralContext.Provider>
  );
}

export function useAttemptEphemeralContext(): AttemptEphemeralContextValue {
  const context = useContext(AttemptEphemeralContext);
  if (context === null)
    throw new Error(
      "useAttemptEphemeralContext must be used inside AttemptEphemeralProvider.",
    );
  return context;
}
