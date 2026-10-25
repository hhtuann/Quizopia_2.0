"use client";

import { useId, useState, type FormEvent } from "react";
import { DateTimeField } from "../../../components/ui/date-time-field";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import type { Configuration } from "../api/publication-contract.generated";
import { configurationSchema } from "../model/publication";
import { PublicationConfirmation } from "./publication-confirmation";
import { PublicationSummary } from "./publication-summary";

const controlClasses =
  "min-h-11 w-full rounded-xl border border-border-strong bg-surface px-4 py-2.5 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus disabled:bg-surface-muted";

export function PublicationConfigurationForm({
  initial,
  disabled = false,
  isPending = false,
  onSubmit,
  mode,
}: {
  readonly initial: Configuration;
  readonly disabled?: boolean;
  readonly isPending?: boolean;
  readonly onSubmit: (configuration: Configuration) => void;
  readonly mode: "create" | "edit";
}) {
  const id = useId();
  // Datetime fields store full UTC instants (e.g. "2026-10-16T01:30:00.000Z");
  // DateTimeField converts to/from the user's local timezone for display.
  const [fields, setFields] = useState(() => ({
    title: initial.title,
    description: initial.description ?? "",
    audience: initial.audience,
    classroomId: initial.classroomId ?? "",
    monitoringChoice: initial.monitoringChoice,
    startAt: initial.startAt,
    endAt: initial.endAt,
    durationMinutes: String(initial.durationMinutes),
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [review, setReview] = useState<Configuration | null>(null);
  const set = (field: keyof typeof fields, value: string) =>
    setFields((current) => ({ ...current, [field]: value }));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled || isPending || review !== null) return;
    const next: Configuration = {
      title: fields.title,
      description: fields.description || null,
      quizId: initial.quizId,
      quizVersionId: initial.quizVersionId,
      versionNumber: initial.versionNumber,
      audience: fields.audience,
      classroomId: fields.audience === "CLASS" ? fields.classroomId : null,
      startAt: fields.startAt,
      endAt: fields.endAt,
      durationMinutes:
        fields.durationMinutes === "" ? -1 : Number(fields.durationMinutes),
      monitoringChoice: fields.monitoringChoice,
      maxAttempts: initial.maxAttempts ?? 1,
    };
    const parsed = configurationSchema.safeParse(next);
    const nextErrors: Record<string, string> = {};
    if (!parsed.success)
      for (const issue of parsed.error.issues)
        nextErrors[String(issue.path[0])] ??= issue.message;
    if (next.durationMinutes === 0 && initial.durationMinutes !== 0)
      nextErrors.durationMinutes =
        "Enter a whole number from 5 to 240 minutes.";
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0 && parsed.success)
      setReview(parsed.data);
  }

  const descriptionLabel = mode === "create" ? "New Publication" : "Edit DRAFT";

  return (
    <>
      <form
        className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start"
        noValidate
        onSubmit={submit}
      >
        {/* Main column: form fields */}
        <fieldset
          className="min-w-0 space-y-5"
          disabled={disabled || isPending}
        >
          <legend className="sr-only">Publication configuration</legend>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              Publication details
            </h2>
            <p className="mt-1 text-sm text-foreground-muted">
              Immutable source: version {initial.versionNumber}. Creating a
              Publication does not publish or modify the Quiz Draft.
            </p>
            <div className="mt-5 space-y-4">
              <TextField
                error={errors.title}
                label="Publication title"
                maxLength={300}
                onChange={(event) => set("title", event.target.value)}
                required
                value={fields.title}
              />
              <div className="space-y-2">
                <label
                  className="block text-sm font-semibold"
                  htmlFor={`${id}-description`}
                >
                  Description (optional)
                </label>
                <textarea
                  aria-describedby={
                    errors.description ? `${id}-description-error` : undefined
                  }
                  aria-invalid={!!errors.description}
                  className={controlClasses}
                  id={`${id}-description`}
                  maxLength={5000}
                  onChange={(event) => set("description", event.target.value)}
                  rows={3}
                  value={fields.description}
                />
                {errors.description ? (
                  <p
                    className="text-sm text-danger"
                    id={`${id}-description-error`}
                    role="alert"
                  >
                    {errors.description}
                  </p>
                ) : null}
              </div>
            </div>
          </Surface>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              Audience and monitoring
            </h2>
            <fieldset className="mt-4">
              <legend className="text-sm font-semibold">Audience</legend>
              <div className="mt-2 flex flex-wrap gap-3">
                {(["CLASS", "PUBLIC"] as const).map((audience) => (
                  <label
                    className="flex min-h-11 cursor-pointer items-center gap-2 rounded-xl border border-border px-4 text-sm font-semibold"
                    key={audience}
                  >
                    <input
                      checked={fields.audience === audience}
                      className="size-4 accent-primary focus-visible:ring-2 focus-visible:ring-focus"
                      name={`${id}-audience`}
                      onChange={() =>
                        setFields((current) => ({
                          ...current,
                          audience,
                          classroomId: "",
                          monitoringChoice:
                            audience === "PUBLIC" ? "DISABLED" : "UNCONFIGURED",
                        }))
                      }
                      type="radio"
                      value={audience}
                    />
                    {audience === "CLASS" ? "Classroom" : "Public"}
                  </label>
                ))}
              </div>
            </fieldset>
            {fields.audience === "CLASS" ? (
              <div className="mt-4 space-y-4">
                <TextField
                  error={errors.classroomId}
                  helperText="Enter the canonical ID of one Classroom. The server verifies your authority; no Classroom directory is available here."
                  label="Classroom ID"
                  onChange={(event) => set("classroomId", event.target.value)}
                  required
                  value={fields.classroomId}
                />
                <fieldset aria-describedby={`${id}-monitoring-help`}>
                  <legend className="text-sm font-semibold">
                    Classroom monitoring choice
                  </legend>
                  <div className="mt-2 space-y-2">
                    {(
                      [
                        ["UNCONFIGURED", "Choose later — DRAFT only"],
                        ["DISABLED", "Disabled — no monitoring"],
                        [
                          "ENABLED",
                          "Enabled — Activity Evidence + LiveKit Camera",
                        ],
                      ] as const
                    ).map(([choice, label]) => (
                      <label
                        className="flex min-h-11 items-center gap-3 rounded-lg border border-border p-3 text-sm"
                        key={choice}
                      >
                        <input
                          checked={fields.monitoringChoice === choice}
                          className="size-4 shrink-0 accent-primary focus-visible:ring-2 focus-visible:ring-focus"
                          disabled={
                            choice === "ENABLED" &&
                            initial.durationMinutes === 0
                          }
                          name={`${id}-monitoring`}
                          onChange={() => set("monitoringChoice", choice)}
                          type="radio"
                          value={choice}
                        />
                        {label}
                      </label>
                    ))}
                  </div>
                </fieldset>
                <p
                  className="text-sm leading-6 text-foreground-muted"
                  id={`${id}-monitoring-help`}
                >
                  Choose explicitly before OPEN. Enabled records the Activity
                  Evidence and LiveKit Camera bundle; collection, camera access
                  and privacy readiness require separate runtime approval. This
                  page does not activate monitoring.
                </p>
                {errors.monitoringChoice ? (
                  <p className="text-sm text-danger" role="alert">
                    {errors.monitoringChoice}
                  </p>
                ) : null}
              </div>
            ) : (
              <Alert
                className="mt-4"
                title="Authenticated participation"
                variant="info"
              >
                PUBLIC has no Classroom and monitoring is disabled. Participants
                need current authenticated STUDENT authority. A private locator
                is not anonymous access.
              </Alert>
            )}
          </Surface>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              Participation schedule
            </h2>
            <p className="mt-1 text-sm leading-6 text-foreground-muted">
              Dates and times are shown in your local timezone and converted to
              UTC automatically. Participation end does not close the
              Publication or shorten an existing Attempt.
            </p>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <DateTimeField
                error={errors.startAt}
                label="Participation start"
                onChange={(instant) => set("startAt", instant)}
                required
                value={fields.startAt}
              />
              <DateTimeField
                error={errors.endAt}
                label="Participation end"
                onChange={(instant) => set("endAt", instant)}
                required
                value={fields.endAt}
              />
            </div>
            <div className="mt-4">
              {initial.durationMinutes === 0 ? (
                <Alert title="Existing unlimited duration">
                  This Publication has unlimited Attempts. This bounded workflow
                  preserves that duration and cannot enable monitoring.
                </Alert>
              ) : (
                <TextField
                  error={errors.durationMinutes}
                  helperText="Whole minutes from 5 to 240. The server sets each Attempt deadline when it starts."
                  label="Attempt duration (minutes)"
                  max={240}
                  min={5}
                  onChange={(event) =>
                    set("durationMinutes", event.target.value)
                  }
                  required
                  step={1}
                  type="number"
                  value={fields.durationMinutes}
                />
              )}
            </div>
            <p className="mt-3 text-sm text-foreground-muted">
              Maximum Attempts:{" "}
              {initial.maxAttempts === 0
                ? "unlimited"
                : (initial.maxAttempts ?? 1)}
              . This workflow preserves that setting.
            </p>
          </Surface>
          {Object.keys(errors).length > 0 ? (
            <Alert title="Check the highlighted fields" variant="danger">
              The configuration was not sent. Review the field messages above.
            </Alert>
          ) : null}
        </fieldset>

        {/* Contextual sidebar column */}
        <aside
          aria-label="Publication summary"
          className="sticky top-20 space-y-4 lg:top-24"
        >
          <Surface className="p-5">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-foreground-muted">
              {descriptionLabel}
            </h2>
            <dl className="mt-3 space-y-2 text-sm">
              <div>
                <dt className="text-foreground-muted">Quiz version</dt>
                <dd className="font-mono text-xs break-all text-foreground">
                  {initial.versionNumber}
                </dd>
              </div>
              <div>
                <dt className="text-foreground-muted">Audience</dt>
                <dd className="font-semibold text-foreground">
                  {fields.audience === "CLASS" ? "Classroom" : "Public"}
                </dd>
              </div>
              {fields.audience === "CLASS" && fields.classroomId ? (
                <div>
                  <dt className="text-foreground-muted">Classroom</dt>
                  <dd className="font-mono text-xs break-all text-foreground">
                    {fields.classroomId}
                  </dd>
                </div>
              ) : null}
              <div>
                <dt className="text-foreground-muted">Duration</dt>
                <dd className="font-semibold text-foreground">
                  {initial.durationMinutes === 0
                    ? "Unlimited"
                    : `${fields.durationMinutes || "—"} min`}
                </dd>
              </div>
            </dl>
          </Surface>
          <Button
            aria-label={isPending ? "Saving Publication" : undefined}
            className="w-full"
            disabled={disabled}
            isLoading={isPending}
            loadingLabel="Saving Publication"
            type="submit"
          >
            {mode === "create"
              ? "Review and create DRAFT"
              : "Review DRAFT changes"}
          </Button>
        </aside>
      </form>
      {review !== null ? (
        <PublicationConfirmation
          confirmLabel={mode === "create" ? "Create DRAFT" : "Save DRAFT"}
          onCancel={() => setReview(null)}
          onConfirm={() => {
            const configuration = review;
            setReview(null);
            onSubmit(configuration);
          }}
          title={
            mode === "create"
              ? "Review new Publication"
              : "Review DRAFT changes"
          }
        >
          <PublicationSummary configuration={review} />
          <p>This saves a DRAFT only. OPEN is a separate reviewed action.</p>
        </PublicationConfirmation>
      ) : null}
    </>
  );
}
