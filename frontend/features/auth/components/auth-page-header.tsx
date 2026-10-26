"use client";

import { useI18n } from "../../../lib/i18n/provider";
import type { TranslationKey } from "../../../lib/i18n/core";

interface AuthPageHeaderProps {
  readonly description: TranslationKey;
  readonly title: TranslationKey;
}

export function AuthPageHeader({ description, title }: AuthPageHeaderProps) {
  const { t } = useI18n();
  return (
    <header>
      <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
        {t("Quizopia account")}
      </p>
      <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground">
        {t(title)}
      </h1>
      <p className="mt-3 text-base leading-7 text-foreground-secondary">
        {t(description)}
      </p>
    </header>
  );
}
