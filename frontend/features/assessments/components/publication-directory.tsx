"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import {
  Button,
  PRIMARY_LINK_CLASSES,
  OUTLINE_LINK_CLASSES,
} from "../../../components/ui/button";
import { HeroOrb } from "../../../components/ui/hero-orb";
import { SelectInput } from "../../../components/ui/select-input";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { formatInstant, formatNumber } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import {
  publicationFailureMessage,
  type PublicationApiClient,
  type PublicationDirectoryQuery,
} from "../api/publication-api-client";
import type { PublicationSummary } from "../api/publication-contract.generated";

export function PublicationDirectory({
  client,
}: {
  readonly client: PublicationApiClient;
}) {
  const { t, localize, locale } = useI18n();
  const [search, setSearch] = useState("");
  const [state, setState] = useState<PublicationDirectoryQuery["state"]>();
  const [query, setQuery] = useState<PublicationDirectoryQuery>({});
  const [items, setItems] = useState<readonly PublicationSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    client.list(query, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setLoading(false);
      if (!result.ok) {
        setError(publicationFailureMessage(result.error));
        return;
      }
      setError(null);
      setCursor(result.value.nextCursor);
      setItems((previous) => {
        const combined = query.cursor
          ? [...previous, ...result.value.items]
          : result.value.items;
        return [
          ...new Map(
            combined.map((item) => [item.publicationId, item]),
          ).values(),
        ];
      });
    });
    return () => controller.abort();
  }, [client, query, retry]);
  function load(next: PublicationDirectoryQuery) {
    setLoading(true);
    setError(null);
    if (!next.cursor) {
      setItems([]);
      setCursor(null);
    }
    setQuery(next);
  }
  const filtered = !!query.search || !!query.state;
  return (
    <div className="space-y-6">
      {/* UX-02 hero card: same pattern as the Quiz Library hero
          (quiz-authoring-pages.tsx) — rounded-2xl, border-primary/10,
          bg-surface, shadow-card, shared HeroOrb, heading + description +
          primary CTA inside the card. The creation helper line lives inside
          the card next to the CTA so the guidance stays attached to the
          action at every viewport. */}
      <div className="relative flex flex-col gap-5 overflow-hidden rounded-2xl border border-primary/10 bg-surface p-6 shadow-card sm:flex-row sm:items-center sm:justify-between sm:p-8">
        <HeroOrb className="-right-10 -top-20 size-72" />
        <div className="max-w-2xl">
          <p className="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {t("Teaching workspace")}
          </p>
          <h1 className="mt-3 font-heading text-3xl font-extrabold tracking-[-0.035em] text-foreground sm:text-4xl">
            {t("My Assessments")}
          </h1>
          <p className="mt-3 text-base leading-7 text-foreground-secondary">
            {t("Manage your assessment Publications, schedules and results.")}
          </p>
          <p className="mt-2 text-sm leading-6 text-foreground-muted">
            {t(
              "To create an assessment, choose an immutable published version in your Quiz Library.",
            )}
          </p>
        </div>
        <Link
          className={`${PRIMARY_LINK_CLASSES} relative shrink-0`}
          href="/app/quizzes"
        >
          {t("Create assessment")}
        </Link>
      </div>
      <Surface className="p-5">
        <form
          className="grid items-end gap-4 sm:grid-cols-[minmax(0,1fr)_auto_auto]"
          onSubmit={(event) => {
            event.preventDefault();
            load({ search: search.trim(), state });
          }}
        >
          <TextField
            label={t("Search assessments")}
            maxLength={300}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            type="search"
          />
          <label className="grid gap-2 text-sm font-semibold">
            {t("Publication state")}
            <SelectInput
              className="border-border-strong pl-4"
              value={state ?? ""}
              onChange={(event) => {
                const next = (event.target.value ||
                  undefined) as PublicationDirectoryQuery["state"];
                setState(next);
                load({ search: search.trim(), state: next });
              }}
            >
              <option value="">{t("All states")}</option>
              {/* Canonical API enum values stay verbatim by policy. */}
              <option value="DRAFT">DRAFT</option>
              <option value="OPEN">OPEN</option>
              <option value="CLOSED">CLOSED</option>
            </SelectInput>
          </label>
          <Button type="submit" variant="secondary">
            {t("Search")}
          </Button>
        </form>
      </Surface>
      {error ? (
        <Alert title={t("Assessments could not be loaded")} variant="danger">
          {localize(error)}
          <div className="mt-3">
            <Button
              variant="secondary"
              onClick={() => {
                setLoading(true);
                setError(null);
                setRetry((value) => value + 1);
              }}
            >
              {t("Retry assessments")}
            </Button>
          </div>
        </Alert>
      ) : null}
      <section aria-label={t("Owned assessments")} aria-busy={loading}>
        {items.length ? (
          <div className="overflow-hidden rounded-xl border border-border bg-surface">
            <div
              className="hidden grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] gap-4 bg-surface-muted px-5 py-3 text-sm font-semibold md:grid"
              aria-hidden="true"
            >
              <span>{t("Assessment")}</span>
              <span>{t("State / audience")}</span>
              <span>{t("Participation window")}</span>
              <span>{t("Actions")}</span>
            </div>
            <ul className="divide-y divide-border">
              {items.map((item) => (
                /* UX-04 row keyboard pattern: the primary "Manage" link IS the
                   row title link (same destination and localized accessible
                   name "Manage {title}"), so the row's main navigation is a
                   real, natively focusable anchor — Enter activates it. The
                   nested "Results" link stays a separately tabbable secondary
                   anchor; because only real anchors are interactive there is
                   no row-level activation to conflict with, and the whole row
                   shows its focus state through :focus-within ring styling. */
                <li
                  key={item.publicationId}
                  className="grid gap-4 rounded-xl p-5 focus-within:ring-2 focus-within:ring-inset focus-within:ring-focus md:grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] md:items-center"
                >
                  <div className="min-w-0">
                    <h2 className="break-words font-semibold">
                      <Link
                        aria-label={t("Manage {title}", { title: item.title })}
                        className="rounded-xs text-foreground underline decoration-primary/40 underline-offset-4 transition-colors hover:decoration-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
                        href={`/app/assessments/${item.publicationId}`}
                      >
                        {item.title}
                      </Link>
                    </h2>
                    <p className="mt-1 text-sm text-foreground-muted">
                      {t("Quiz version {versionNumber}", {
                        versionNumber: item.versionNumber,
                      })}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {/* Canonical API state enum: rendered verbatim by policy. */}
                    <span
                      className={`rounded-lg px-2 py-1 font-mono text-xs font-semibold ${item.state === "OPEN" ? "bg-success/10 text-foreground" : item.state === "CLOSED" ? "bg-surface-muted text-foreground-secondary" : "bg-warning/10 text-foreground"}`}
                    >
                      {item.state}
                    </span>
                    <span className="text-sm text-foreground-secondary">
                      {item.audience === "CLASS" ? t("Classroom") : t("Public")}
                    </span>
                  </div>
                  <div className="text-sm text-foreground-secondary">
                    <p>
                      <span className="md:sr-only">{t("Start:")}</span>{" "}
                      <time dateTime={item.startAt}>
                        {formatInstant(locale, item.startAt, {
                          withTimeZone: true,
                        })}
                      </time>
                    </p>
                    <p>
                      <span className="md:sr-only">{t("End:")}</span>{" "}
                      <time dateTime={item.endAt}>
                        {formatInstant(locale, item.endAt, {
                          withTimeZone: true,
                        })}
                      </time>
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {item.state !== "DRAFT" ? (
                      <Link
                        aria-label={t("Results for {title}", {
                          title: item.title,
                        })}
                        className={OUTLINE_LINK_CLASSES}
                        href={`/app/assessments/${item.publicationId}/results`}
                      >
                        {t("Results")}
                      </Link>
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          </div>
        ) : !loading && !error ? (
          <Surface className="p-8 text-center">
            <h2 className="text-xl font-bold">
              {filtered
                ? t("No matching assessments")
                : t("No assessments yet")}
            </h2>
            <p className="mt-3 text-foreground-secondary">
              {filtered
                ? t("Try another title or state to find your assessment.")
                : t(
                    "Create your first assessment from a published QuizVersion.",
                  )}
            </p>
            {filtered ? (
              <Button
                className="mt-5"
                variant="secondary"
                onClick={() => {
                  setSearch("");
                  setState(undefined);
                  load({});
                }}
              >
                {t("Clear filters")}
              </Button>
            ) : (
              <Link
                className={`${OUTLINE_LINK_CLASSES} mt-5`}
                href="/app/quizzes"
              >
                {t("Open Quiz Library")}
              </Link>
            )}
          </Surface>
        ) : null}
        {loading ? (
          <p
            className="py-6 text-center text-foreground-secondary"
            role="status"
          >
            {t("Loading assessments…")}
          </p>
        ) : (
          <p className="mt-4 text-sm text-foreground-muted" role="status">
            {t("{count} assessments shown", {
              count: formatNumber(locale, items.length),
            })}
          </p>
        )}
      </section>
      {cursor && !error ? (
        <Button
          variant="secondary"
          disabled={loading}
          onClick={() => load({ ...query, cursor })}
        >
          {t("Load more assessments")}
        </Button>
      ) : null}
    </div>
  );
}
