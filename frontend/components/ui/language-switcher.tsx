"use client";

import { useI18n } from "../../lib/i18n/provider";
import type { Locale } from "../../lib/i18n/core";
import { SelectInput } from "./select-input";

export function LanguageSwitcher() {
  const { locale, setLocale } = useI18n();
  return (
    <label className="inline-flex min-h-11 shrink-0 items-center gap-2 text-sm text-foreground-secondary">
      <span className="sr-only">
        {locale === "vi" ? "Ngôn ngữ" : "Language"}
      </span>
      <SelectInput
        aria-label={locale === "vi" ? "Ngôn ngữ" : "Language"}
        value={locale}
        onChange={(event) => setLocale(event.target.value as Locale)}
      >
        <option lang="vi" value="vi">
          Tiếng Việt
        </option>
        <option lang="en" value="en">
          English
        </option>
      </SelectInput>
    </label>
  );
}
