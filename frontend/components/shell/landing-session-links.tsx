"use client";

import Link from "next/link";
import { useI18n } from "../../lib/i18n/provider";
import { useAuth } from "../../features/auth/auth-provider";
import { OUTLINE_LINK_CLASSES, PRIMARY_LINK_CLASSES } from "../ui/button";
import { ArrowRightIcon, ArrowUpRightIcon } from "../ui/icons";

interface LandingSessionLinksProps {
  readonly placement: "header" | "hero";
}

export function LandingSessionLinks({ placement }: LandingSessionLinksProps) {
  const { t } = useI18n();
  const { session } = useAuth();
  if (session.status === "bootstrapping") {
    return (
      <span
        aria-label={t("Checking session")}
        className="min-h-11 min-w-32 animate-pulse rounded-xl bg-surface-muted motion-reduce:animate-none"
        role="status"
      />
    );
  }

  const signedIn =
    session.status === "authenticated" || session.status === "refreshing";
  if (signedIn) {
    return (
      <Link className={PRIMARY_LINK_CLASSES} href="/app">
        {placement === "header"
          ? t("Open workspace")
          : t("Continue to your workspace")}
        <ArrowRightIcon className="size-4 shrink-0" />
      </Link>
    );
  }

  if (placement === "header") {
    return (
      <>
        <Link className={OUTLINE_LINK_CLASSES} href="/login">
          {t("Sign in")}
        </Link>
        <Link className={`${PRIMARY_LINK_CLASSES} group`} href="/register">
          {t("Get started")}
          <ArrowUpRightIcon className="size-4 shrink-0 transition-transform motion-safe:group-hover:-translate-y-0.5 motion-safe:group-hover:translate-x-0.5 motion-reduce:transition-none" />
        </Link>
      </>
    );
  }

  return (
    <>
      <Link className={PRIMARY_LINK_CLASSES} href="/register">
        {t("Create your account")}
        <ArrowRightIcon className="size-4 shrink-0" />
      </Link>
      <Link className={OUTLINE_LINK_CLASSES} href="/login">
        {t("Open your workspace")}
      </Link>
    </>
  );
}
