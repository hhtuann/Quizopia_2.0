"use client";

import { useEffect } from "react";

const DEFAULT_MESSAGE =
  "This Attempt has unsaved or unresolved work. Leave this page anyway?";

export function useUnsavedNavigationGuard(
  active: boolean,
  message = DEFAULT_MESSAGE,
) {
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!active) return;
      event.preventDefault();
    };
    const beforeLinkNavigation = (event: MouseEvent) => {
      if (!active || event.defaultPrevented || event.button !== 0) return;
      const target = event.target;
      const link =
        target instanceof Element
          ? target.closest<HTMLAnchorElement>("a[href]")
          : null;
      if (link === null || link.target === "_blank" || window.confirm(message))
        return;
      event.preventDefault();
      event.stopPropagation();
    };
    const navigation = (
      window as Window & {
        readonly navigation?: EventTarget & {
          readonly currentEntry?: { readonly url?: string };
        };
      }
    ).navigation;
    const beforeHistoryNavigation = (event: Event) => {
      const navigateEvent = event as Event & {
        readonly navigationType?: string;
      };
      const guardedUrl = navigation?.currentEntry?.url ?? window.location.href;
      if (
        active &&
        navigateEvent.navigationType === "traverse" &&
        event.cancelable &&
        !window.confirm(message)
      ) {
        event.preventDefault();
        window.setTimeout(() => {
          if (window.location.href !== guardedUrl)
            window.history.pushState(window.history.state, "", guardedUrl);
        }, 0);
      }
    };
    window.addEventListener("beforeunload", beforeUnload);
    document.addEventListener("click", beforeLinkNavigation, true);
    navigation?.addEventListener("navigate", beforeHistoryNavigation);
    return () => {
      window.removeEventListener("beforeunload", beforeUnload);
      document.removeEventListener("click", beforeLinkNavigation, true);
      navigation?.removeEventListener("navigate", beforeHistoryNavigation);
    };
  }, [active, message]);
}
