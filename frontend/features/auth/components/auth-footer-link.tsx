"use client";

import Link from "next/link";
import { useI18n } from "../../../lib/i18n/provider";
import type { TranslationKey } from "../../../lib/i18n/core";

export function AuthFooterLink({
  prompt,
  label,
  href,
}: {
  readonly prompt: TranslationKey;
  readonly label: TranslationKey;
  readonly href: string;
}) {
  const { t } = useI18n();
  return (
    <p className="mt-6 text-center text-sm leading-6 text-foreground-secondary">
      {t(prompt)}{" "}
      <Link
        className="rounded font-semibold text-primary underline-offset-4 hover:text-primary-hover hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
        href={href}
      >
        {t(label)}
      </Link>
    </p>
  );
}
