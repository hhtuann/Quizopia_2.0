"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert } from "../../../components/ui/alert";
import {
  Button,
  PRIMARY_LINK_CLASSES,
  OUTLINE_LINK_CLASSES,
} from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
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
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-primary">
            Teaching workspace
          </p>
          <h1 className="mt-2 font-heading text-3xl font-bold">
            My <span className="brand-gradient-text">Assessments</span>
          </h1>
          <p className="mt-3 text-foreground-secondary">
            Manage your assessment Publications, schedules and results.
          </p>
        </div>
        <Link className={PRIMARY_LINK_CLASSES} href="/app/quizzes">
          Create assessment
        </Link>
      </div>
      <p className="text-sm text-foreground-muted">
        To create an assessment, choose an immutable published version in your
        Quiz Library.
      </p>
      <Surface className="p-5">
        <form
          className="grid items-end gap-4 sm:grid-cols-[minmax(0,1fr)_auto_auto]"
          onSubmit={(event) => {
            event.preventDefault();
            load({ search: search.trim(), state });
          }}
        >
          <TextField
            label="Search assessments"
            maxLength={300}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            type="search"
          />
          <label className="grid gap-2 text-sm font-semibold">
            Publication state
            <select
              className="min-h-11 rounded-xl border border-border-strong bg-surface px-4 focus-visible:ring-2 focus-visible:ring-focus"
              value={state ?? ""}
              onChange={(event) => {
                const next = (event.target.value ||
                  undefined) as PublicationDirectoryQuery["state"];
                setState(next);
                load({ search: search.trim(), state: next });
              }}
            >
              <option value="">All states</option>
              <option value="DRAFT">DRAFT</option>
              <option value="OPEN">OPEN</option>
              <option value="CLOSED">CLOSED</option>
            </select>
          </label>
          <Button type="submit" variant="secondary">
            Search
          </Button>
        </form>
      </Surface>
      {error ? (
        <Alert title="Assessments could not be loaded" variant="danger">
          {error}
          <div className="mt-3">
            <Button
              variant="secondary"
              onClick={() => {
                setLoading(true);
                setError(null);
                setRetry((value) => value + 1);
              }}
            >
              Retry assessments
            </Button>
          </div>
        </Alert>
      ) : null}
      <section aria-label="Owned assessments" aria-busy={loading}>
        {items.length ? (
          <div className="overflow-hidden rounded-xl border border-border bg-surface">
            <div
              className="hidden grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] gap-4 bg-surface-muted px-5 py-3 text-sm font-semibold md:grid"
              aria-hidden="true"
            >
              <span>Assessment</span>
              <span>State / audience</span>
              <span>Participation window</span>
              <span>Actions</span>
            </div>
            <ul className="divide-y divide-border">
              {items.map((item) => (
                <li
                  key={item.publicationId}
                  className="grid gap-4 p-5 md:grid-cols-[minmax(0,2fr)_1fr_1fr_1fr] md:items-center"
                >
                  <div className="min-w-0">
                    <h2 className="break-words font-semibold">{item.title}</h2>
                    <p className="mt-1 text-sm text-foreground-muted">
                      Quiz version {item.versionNumber}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span
                      className={`rounded-lg px-2 py-1 font-mono text-xs font-semibold ${item.state === "OPEN" ? "bg-success/10 text-foreground" : item.state === "CLOSED" ? "bg-surface-muted text-foreground-secondary" : "bg-warning/10 text-foreground"}`}
                    >
                      {item.state}
                    </span>
                    <span className="text-sm text-foreground-secondary">
                      {item.audience === "CLASS" ? "Classroom" : "Public"}
                    </span>
                  </div>
                  <div className="text-sm text-foreground-secondary">
                    <p>
                      <span className="md:sr-only">Start: </span>
                      <time dateTime={item.startAt}>
                        {new Date(item.startAt).toLocaleString()}
                      </time>
                    </p>
                    <p>
                      <span className="md:sr-only">End: </span>
                      <time dateTime={item.endAt}>
                        {new Date(item.endAt).toLocaleString()}
                      </time>
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Link
                      aria-label={`Manage ${item.title}`}
                      className={OUTLINE_LINK_CLASSES}
                      href={`/app/assessments/${item.publicationId}`}
                    >
                      Manage
                    </Link>
                    {item.state !== "DRAFT" ? (
                      <Link
                        aria-label={`Results for ${item.title}`}
                        className={OUTLINE_LINK_CLASSES}
                        href={`/app/assessments/${item.publicationId}/results`}
                      >
                        Results
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
              {filtered ? "No matching assessments" : "No assessments yet"}
            </h2>
            <p className="mt-3 text-foreground-secondary">
              {filtered
                ? "Try another title or state to find your assessment."
                : "Create your first assessment from a published QuizVersion."}
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
                Clear filters
              </Button>
            ) : (
              <Link
                className={`${OUTLINE_LINK_CLASSES} mt-5`}
                href="/app/quizzes"
              >
                Open Quiz Library
              </Link>
            )}
          </Surface>
        ) : null}
        {loading ? (
          <p
            className="py-6 text-center text-foreground-secondary"
            role="status"
          >
            Loading assessments…
          </p>
        ) : (
          <p className="mt-4 text-sm text-foreground-muted" role="status">
            {items.length} assessments shown
          </p>
        )}
      </section>
      {cursor && !error ? (
        <Button
          variant="secondary"
          disabled={loading}
          onClick={() => load({ ...query, cursor })}
        >
          Load more assessments
        </Button>
      ) : null}
    </div>
  );
}
