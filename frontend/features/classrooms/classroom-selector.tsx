"use client";

import { useEffect, useId, useState } from "react";
import { Alert } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { publicationFailureMessage } from "../assessments/api/publication-api-client";
import type {
  ClassroomApiClient,
  SelectedClassroom,
} from "./api/classroom-api-client";
import { useClassroomClient } from "./use-classroom-client";

export function OwnedClassroomSelector(props: SelectorProps) {
  const client = useClassroomClient();
  return <ClassroomSelector {...props} client={client} />;
}
interface SelectorProps {
  readonly value: string;
  readonly onChange: (id: string) => void;
  readonly onValidityChange: (valid: boolean) => void;
  readonly error?: string;
}

export function ClassroomSelector({
  client,
  value,
  onChange,
  onValidityChange,
  error,
}: SelectorProps & { readonly client: ClassroomApiClient | null }) {
  const id = useId();
  const [items, setItems] = useState<readonly SelectedClassroom[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [requestCursor, setRequestCursor] = useState<string>();
  const [retry, setRetry] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);
  const [resolved, setResolved] = useState<SelectedClassroom | null>(null);
  const [selectionFailure, setSelectionFailure] = useState<string | null>(null);
  const [selectionRetry, setSelectionRetry] = useState(0);
  useEffect(() => {
    if (!client) return;
    const controller = new AbortController();
    client.list(requestCursor, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      setLoading(false);
      if (!result.ok) {
        setFailure(publicationFailureMessage(result.error));
        return;
      }
      setFailure(null);
      setCursor(result.value.nextCursor);
      setItems((previous) => [
        ...new Map(
          [...(requestCursor ? previous : []), ...result.value.items].map(
            (item) => [item.id, item],
          ),
        ).values(),
      ]);
    });
    return () => controller.abort();
  }, [client, requestCursor, retry]);
  const listed = items.find((item) => item.id === value);
  useEffect(() => {
    const controller = new AbortController();
    onValidityChange(!!listed);
    if (!value || listed || !client) return () => controller.abort();
    client.get(value, controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      if (result.ok) {
        setResolved(result.value);
        setSelectionFailure(null);
        onValidityChange(true);
      } else {
        setResolved(null);
        setSelectionFailure(publicationFailureMessage(result.error));
        onValidityChange(false);
      }
    });
    return () => controller.abort();
  }, [client, value, listed, selectionRetry, onValidityChange]);
  const selected = listed ?? (resolved?.id === value ? resolved : null);
  const choices = selected && !listed ? [selected, ...items] : items;
  return (
    <fieldset
      aria-describedby={`${id}-help${error ? ` ${id}-error` : ""}`}
      className="space-y-3"
    >
      <legend className="text-sm font-semibold">Choose one Classroom</legend>
      <p className="text-sm text-foreground-muted" id={`${id}-help`}>
        Only your owned Classrooms are available. Select one to assign this
        assessment.
      </p>
      {!client ? (
        <Alert title="Classroom access unavailable" variant="warning">
          Your authenticated Classroom connection is unavailable.
        </Alert>
      ) : null}
      {value && !selected ? (
        <Alert title="Selected Classroom needs verification" variant="warning">
          {selectionFailure ?? "Checking access to the saved Classroom…"}
          {selectionFailure ? (
            <Button
              className="mt-3"
              type="button"
              variant="secondary"
              onClick={() => {
                setSelectionFailure(null);
                setSelectionRetry((current) => current + 1);
              }}
            >
              Retry selected Classroom
            </Button>
          ) : null}
        </Alert>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2">
        {choices.map((item) => (
          <label
            className={`flex min-h-11 min-w-0 cursor-pointer items-start gap-3 rounded-xl border p-4 focus-within:ring-2 focus-within:ring-focus ${value === item.id ? "border-primary bg-primary/5" : "border-border bg-surface"}`}
            key={item.id}
          >
            <input
              className="mt-1 size-4 shrink-0 accent-primary"
              type="radio"
              name={`${id}-classroom`}
              value={item.id}
              checked={value === item.id}
              onChange={() => {
                setSelectionFailure(null);
                onChange(item.id);
              }}
              aria-labelledby={`${id}-${item.id}-name`}
              aria-describedby={`${id}-${item.id}`}
            />
            <span className="min-w-0">
              <span
                className="block break-words font-semibold"
                id={`${id}-${item.id}-name`}
              >
                {item.name}
              </span>
              <span
                className="mt-1 block break-words text-sm text-foreground-secondary"
                id={`${id}-${item.id}`}
              >
                {item.subject} · {item.grade}
                {item.description ? (
                  <span className="mt-1 block">{item.description}</span>
                ) : null}
              </span>
            </span>
          </label>
        ))}
      </div>
      {loading && client ? (
        <p role="status" className="text-sm text-foreground-secondary">
          Loading your Classrooms…
        </p>
      ) : null}
      {failure ? (
        <Alert title="Classrooms could not be loaded" variant="danger">
          {failure}
          <div className="mt-3">
            <Button
              type="button"
              variant="secondary"
              onClick={() => {
                setLoading(true);
                setFailure(null);
                setRetry((current) => current + 1);
              }}
            >
              Retry Classrooms
            </Button>
          </div>
        </Alert>
      ) : null}
      {!loading && !failure && choices.length === 0 ? (
        <p className="rounded-xl bg-surface-muted p-4 text-sm">
          No owned Classrooms yet. A Classroom must be created before you can
          assign a CLASS assessment. You can save a PUBLIC assessment instead.
        </p>
      ) : null}
      {cursor && !failure ? (
        <Button
          disabled={loading}
          type="button"
          variant="secondary"
          onClick={() => {
            setLoading(true);
            setRequestCursor(cursor);
          }}
        >
          Load more Classrooms
        </Button>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-danger" id={`${id}-error`}>
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}
