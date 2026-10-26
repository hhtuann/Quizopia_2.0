"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  DEFAULT_LOCALE,
  LOCALE_COOKIE,
  localeTags,
  resolveLocale,
  translate,
  translateMessage,
  type Locale,
  type TranslationKey,
  type TranslationValues,
} from "./core";

interface I18nContextValue {
  readonly locale: Locale;
  readonly localeTag: string;
  readonly setLocale: (locale: Locale) => void;
  readonly t: (key: TranslationKey, values?: TranslationValues) => string;
  /** Explicit presentation lookup for domain error/state messages, never user content. */
  readonly localize: (message: string, values?: TranslationValues) => string;
}

// Isolated legacy component tests render English without a provider. All real
// routes are enclosed by I18nProvider and default to vi on server and client.
const fallback: I18nContextValue = {
  locale: "en",
  localeTag: localeTags.en,
  setLocale: () => {},
  t: (key, values) => translate("en", key, values),
  localize: (message, values) => translateMessage("en", message, values),
};
const I18nContext = createContext<I18nContextValue>(fallback);

export function I18nProvider({
  children,
  initialLocale = DEFAULT_LOCALE,
}: {
  readonly children: ReactNode;
  readonly initialLocale?: Locale;
}) {
  const [locale, updateLocale] = useState<Locale>(initialLocale);
  const setLocale = useCallback((next: Locale) => {
    const valid = resolveLocale(next);
    // Preference only; no credentials, account or role data is persisted.
    document.cookie = `${LOCALE_COOKIE}=${valid}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
    updateLocale(valid);
    document.documentElement.lang = valid;
  }, []);
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  const value = useMemo<I18nContextValue>(
    () => ({
      locale,
      localeTag: localeTags[locale],
      setLocale,
      t: (key, values) => translate(locale, key, values),
      localize: (message, values) => translateMessage(locale, message, values),
    }),
    [locale, setLocale],
  );
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  return useContext(I18nContext);
}
