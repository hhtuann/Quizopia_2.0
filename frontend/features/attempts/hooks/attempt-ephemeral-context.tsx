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
  const value = useMemo(
    () => ({ getPublicLocator, rememberPublicLocator }),
    [getPublicLocator, rememberPublicLocator],
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
