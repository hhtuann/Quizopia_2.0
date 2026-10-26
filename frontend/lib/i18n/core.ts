import { commonMessages } from "./messages/common";
import { authoringMessages } from "./messages/authoring";
import { assessmentMessages } from "./messages/assessment";
import { studentMessages } from "./messages/student";
import { monitoringMessages } from "./messages/monitoring";

export type Locale = "vi" | "en";
export const DEFAULT_LOCALE: Locale = "vi";
export const LOCALE_COOKIE = "quizopia-locale";
export const localeTags: Record<Locale, string> = { vi: "vi-VN", en: "en-GB" };

// English source phrases are stable typed catalog keys; their Vietnamese values
// are presentation only. API identifiers, source Markdown and user content never
// pass through this lookup automatically.
export const messages = {
  ...commonMessages,
  ...authoringMessages,
  ...assessmentMessages,
  ...studentMessages,
  ...monitoringMessages,
} as const;
export type TranslationKey = keyof typeof messages;
export type TranslationValues = Readonly<Record<string, string | number>>;

export function resolveLocale(value?: string | null): Locale {
  return value === "vi" || value === "en" ? value : DEFAULT_LOCALE;
}

export function hasTranslation(key: string): key is TranslationKey {
  return Object.hasOwn(messages, key);
}

/** Deterministic English fallback for explicitly localized unknown messages. */
export function translateMessage(
  locale: Locale,
  key: string,
  values: TranslationValues = {},
): string {
  const template = locale === "vi" && hasTranslation(key) ? messages[key] : key;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : match,
  );
}

export function translate(
  locale: Locale,
  key: TranslationKey,
  values?: TranslationValues,
): string {
  return translateMessage(locale, key, values);
}

export function formatNumber(
  locale: Locale,
  value: number | bigint,
  options?: Intl.NumberFormatOptions,
): string {
  return new Intl.NumberFormat(localeTags[locale], options).format(value);
}

/** Display only: decimal strings remain exact in API/state and grading logic. */
export function formatDecimal(locale: Locale, value: string): string {
  if (!/^-?\d+(?:\.\d+)?$/.test(value)) return value;
  const [whole, fraction] = value.split(".");
  const integral = new Intl.NumberFormat(localeTags[locale]).format(
    BigInt(whole),
  );
  if (fraction === undefined) return integral;
  const separator =
    new Intl.NumberFormat(localeTags[locale])
      .formatToParts(1.1)
      .find((part) => part.type === "decimal")?.value ?? ".";
  return `${integral}${separator}${fraction}`;
}

export function formatDateTime(
  locale: Locale,
  value: string | number | Date,
  options: Intl.DateTimeFormatOptions = {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  },
): string {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  return new Intl.DateTimeFormat(localeTags[locale], options).format(date);
}
