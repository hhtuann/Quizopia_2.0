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

export const DISPLAY_TIME_ZONE = "Asia/Ho_Chi_Minh";

export interface FormatInstantOptions {
  readonly withTimeZone?: boolean;
  readonly dateStyle?: "full" | "long" | "medium" | "short" | "none";
  readonly timeStyle?: "short" | "medium" | "none";
}

/** Derived once from the zone itself (Asia/Ho_Chi_Minh has a fixed offset). */
const DISPLAY_ZONE_OFFSET_LABEL: string = (() => {
  for (const timeZoneName of ["shortOffset", "longOffset"] as const) {
    const label = new Intl.DateTimeFormat("en-US", {
      timeZone: DISPLAY_TIME_ZONE,
      timeZoneName,
    })
      .formatToParts(new Date())
      .find((part) => part.type === "timeZoneName")?.value;
    if (label !== undefined && label.startsWith("GMT") && label !== "GMT")
      return label.replace("GMT", "UTC");
  }
  return "UTC+7";
})();

function toInstantDate(
  value: string | number | Date | null | undefined,
): Date | null {
  if (value === null || value === undefined || value === "") return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime()) ? date : null;
}

function zoneDateKey(date: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    timeZone: DISPLAY_TIME_ZONE,
  }).format(date);
}

function zoneTime(date: Date, withSeconds: boolean): string {
  const parts = new Intl.DateTimeFormat(localeTags.vi, {
    hour: "2-digit",
    minute: "2-digit",
    ...(withSeconds ? { second: "2-digit" } : {}),
    hourCycle: "h23",
    timeZone: DISPLAY_TIME_ZONE,
  }).formatToParts(date);
  const value = (type: string) =>
    parts.find((part) => part.type === type)?.value ?? "";
  return withSeconds
    ? `${value("hour")}:${value("minute")}:${value("second")}`
    : `${value("hour")}:${value("minute")}`;
}

function zoneNumericDate(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    timeZone: DISPLAY_TIME_ZONE,
  }).formatToParts(date);
  const value = (type: string) =>
    parts.find((part) => part.type === type)?.value ?? "";
  return `${value("day")}/${value("month")}/${value("year")}`;
}

function formatViInstant(
  date: Date,
  dateStyle: NonNullable<FormatInstantOptions["dateStyle"]>,
  timeStyle: NonNullable<FormatInstantOptions["timeStyle"]>,
): string {
  const chunks: string[] = [];
  if (timeStyle !== "none") chunks.push(zoneTime(date, timeStyle === "medium"));
  if (dateStyle !== "none") {
    chunks.push(
      dateStyle === "medium" || dateStyle === "short"
        ? zoneNumericDate(date)
        : new Intl.DateTimeFormat(localeTags.vi, {
            dateStyle,
            timeZone: DISPLAY_TIME_ZONE,
          }).format(date),
    );
  }
  return chunks.join(", ");
}

function formatEnInstant(
  date: Date,
  dateStyle: NonNullable<FormatInstantOptions["dateStyle"]>,
  timeStyle: NonNullable<FormatInstantOptions["timeStyle"]>,
): string {
  const options: Intl.DateTimeFormatOptions = {
    timeZone: DISPLAY_TIME_ZONE,
  };
  if (dateStyle !== "none") options.dateStyle = dateStyle;
  if (timeStyle !== "none") options.timeStyle = timeStyle;
  return new Intl.DateTimeFormat(localeTags.en, options).format(date);
}

function zoneSuffix(locale: Locale, enabled: boolean): string {
  if (!enabled) return "";
  return locale === "vi"
    ? ` (giờ Việt Nam, ${DISPLAY_ZONE_OFFSET_LABEL})`
    : ` (Vietnam time, ${DISPLAY_ZONE_OFFSET_LABEL})`;
}

/**
 * Human-friendly display in `DISPLAY_TIME_ZONE` (24-hour; no seconds by
 * default). Display only: server timestamps and deadlines stay authoritative
 * UTC instants. Pass `withTimeZone: true` for business-critical schedules,
 * deadlines and cutoffs so the zone is explicit.
 */
export function formatInstant(
  locale: Locale,
  value: string | number | Date | null | undefined,
  options?: FormatInstantOptions,
): string {
  const date = toInstantDate(value);
  if (date === null) return "—";
  const {
    withTimeZone = false,
    dateStyle = "medium",
    timeStyle = "short",
  } = options ?? {};
  if (dateStyle === "none" && timeStyle === "none") return "—";
  const formatted =
    locale === "vi"
      ? formatViInstant(date, dateStyle, timeStyle)
      : formatEnInstant(date, dateStyle, timeStyle);
  return `${formatted}${zoneSuffix(locale, withTimeZone)}`;
}

/** Same-day windows collapse to one date; otherwise both instants render. */
export function formatInstantRange(
  locale: Locale,
  start: string | number | Date | null | undefined,
  end: string | number | Date | null | undefined,
  options?: FormatInstantOptions,
): string {
  const startDate = toInstantDate(start);
  const endDate = toInstantDate(end);
  if (startDate === null || endDate === null) return "—";
  const {
    withTimeZone = false,
    dateStyle = "medium",
    timeStyle = "short",
  } = options ?? {};
  const suffix = zoneSuffix(locale, withTimeZone);
  if (zoneDateKey(startDate) !== zoneDateKey(endDate)) {
    return `${formatInstant(locale, startDate, { dateStyle, timeStyle })} – ${formatInstant(locale, endDate, { dateStyle, timeStyle })}${suffix}`;
  }
  if (timeStyle === "none") {
    return `${formatInstant(locale, startDate, { dateStyle, timeStyle })}${suffix}`;
  }
  const date = formatInstant(locale, startDate, {
    dateStyle,
    timeStyle: "none",
  });
  const startTime = formatInstant(locale, startDate, {
    dateStyle: "none",
    timeStyle,
  });
  const endTime = formatInstant(locale, endDate, {
    dateStyle: "none",
    timeStyle,
  });
  return locale === "vi"
    ? `${startTime}, ${date} – ${endTime}${suffix}`
    : `${date}, ${startTime} – ${endTime}${suffix}`;
}
