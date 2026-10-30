"use client";

import { useEffect, useId, useMemo, useState, type FormEvent } from "react";
import { DateTimeField } from "../../../components/ui/date-time-field";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { Surface } from "../../../components/ui/surface";
import { TextField } from "../../../components/ui/text-field";
import { useI18n } from "../../../lib/i18n/provider";
import { useUnsavedNavigationGuard } from "../../attempts/hooks/use-unsaved-navigation-guard";
import type { Configuration } from "../api/publication-contract.generated";
import { configurationSchema } from "../model/publication";
import { PublicationConfirmation } from "./publication-confirmation";
import { PublicationSummary } from "./publication-summary";
import { OwnedClassroomSelector } from "../../classrooms/classroom-selector";

const controlClasses =
  "min-h-11 w-full rounded-xl border border-border-strong bg-surface px-4 py-2.5 text-base text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus disabled:bg-surface-muted";

export function PublicationConfigurationForm({
  initial,
  disabled = false,
  isPending = false,
  onDirtyChange,
  onSubmit,
  mode,
}: {
  readonly initial: Configuration;
  readonly disabled?: boolean;
  readonly isPending?: boolean;
  readonly onSubmit: (configuration: Configuration) => void;
  readonly mode: "create" | "edit";
  /**
   * Notifies the parent whenever the form has unsaved values. The parent owns
   * destructive close controls (e.g. Cancel editing) and must confirm before
   * discarding them. Unsaved-work protection itself is registered here and is
   * deliberately independent of disabled: a form disabled by a revision
   * conflict or blocked state still holds unsaved entered values.
   */
  readonly onDirtyChange?: (dirty: boolean) => void;
}) {
  const { t, localize } = useI18n();
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
    maxAttempts: String(initial.maxAttempts ?? 1),
  }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [review, setReview] = useState<Configuration | null>(null);
  const [classroomValid, setClassroomValid] = useState(false);
  const set = (field: keyof typeof fields, value: string) =>
    setFields((current) => ({ ...current, [field]: value }));

  // A pending create/edit form is unsaved work: leaving via links, reloads or
  // a workspace switch must be cancellable without losing entered values.
  // Protection is NOT gated on disabled: a revision conflict or pending
  // command disables editing but the entered values remain unsaved, and a
  // workspace switch must not silently discard them. Successful saves unmount
  // or navigate this form programmatically, which the guard does not
  // intercept, so clean and saved flows never see a confirmation.
  const initialFields = useMemo(
    () => ({
      title: initial.title,
      description: initial.description ?? "",
      audience: initial.audience,
      classroomId: initial.classroomId ?? "",
      monitoringChoice: initial.monitoringChoice,
      startAt: initial.startAt,
      endAt: initial.endAt,
      durationMinutes: String(initial.durationMinutes),
      maxAttempts: String(initial.maxAttempts ?? 1),
    }),
    [initial],
  );
  const formDirty = useMemo(
    () =>
      (Object.keys(fields) as (keyof typeof fields)[]).some(
        (key) => fields[key] !== initialFields[key],
      ),
    [fields, initialFields],
  );
  // Guard message stays an English catalog key in state; the localized
  // presentation recomputes on every render so a locale switch re-registers
  // the guard with the current language.
  useUnsavedNavigationGuard(
    formDirty,
    localize(
      "This form has unsaved changes. Leave this page and discard them?",
    ),
  );
  useEffect(() => {
    onDirtyChange?.(formDirty);
  }, [formDirty, onDirtyChange]);

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
      maxAttempts: fields.maxAttempts === "" ? -1 : Number(fields.maxAttempts),
    };
    const parsed = configurationSchema.safeParse(next);
    const nextErrors: Record<string, string> = {};
    if (!parsed.success)
      for (const issue of parsed.error.issues)
        nextErrors[String(issue.path[0])] ??= issue.message;
    if (next.durationMinutes === 0 && initial.durationMinutes !== 0)
      nextErrors.durationMinutes =
        "Enter a whole number from 5 to 240 minutes.";
    if (fields.audience === "CLASS" && !classroomValid)
      nextErrors.classroomId =
        "Select an accessible owned Classroom before continuing.";
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0 && parsed.success)
      setReview(parsed.data);
  }

  const descriptionLabel =
    mode === "create" ? t("New Publication") : t("Edit DRAFT");

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
          <legend className="sr-only">{t("Publication configuration")}</legend>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              {t("Publication details")}
            </h2>
            <p className="mt-1 text-sm text-foreground-muted">
              {t(
                "Source: version {versionNumber}. Creating this assessment does not change your Quiz draft.",
                { versionNumber: initial.versionNumber },
              )}
            </p>
            <div className="mt-5 space-y-4">
              <TextField
                error={errors.title ? localize(errors.title) : undefined}
                label={t("Publication title")}
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
                  {t("Description (optional)")}
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
                    {localize(errors.description)}
                  </p>
                ) : null}
              </div>
            </div>
          </Surface>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              {t("Audience and monitoring")}
            </h2>
            <fieldset className="mt-4">
              <legend className="text-sm font-semibold">{t("Audience")}</legend>
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
                    {audience === "CLASS" ? t("Classroom") : t("Public")}
                  </label>
                ))}
              </div>
            </fieldset>
            {fields.audience === "CLASS" ? (
              <div className="mt-4 space-y-4">
                <OwnedClassroomSelector
                  error={
                    errors.classroomId
                      ? localize(errors.classroomId)
                      : undefined
                  }
                  onChange={(value) => {
                    setClassroomValid(false);
                    set("classroomId", value);
                  }}
                  onValidityChange={setClassroomValid}
                  value={fields.classroomId}
                />
                <fieldset aria-describedby={`${id}-monitoring-help`}>
                  <legend className="text-sm font-semibold">
                    {t("Classroom monitoring choice")}
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
                        {t(label)}
                      </label>
                    ))}
                  </div>
                </fieldset>
                <p
                  className="text-sm leading-6 text-foreground-muted"
                  id={`${id}-monitoring-help`}
                >
                  {t(
                    "Choose before OPEN. Enabling here does not start collection — students approve monitoring separately during the attempt.",
                  )}
                </p>
                {errors.monitoringChoice ? (
                  <p className="text-sm text-danger" role="alert">
                    {localize(errors.monitoringChoice)}
                  </p>
                ) : null}
              </div>
            ) : (
              <Alert
                className="mt-4"
                title={t("Authenticated participation")}
                variant="info"
              >
                {t(
                  "PUBLIC has no Classroom and no monitoring. Students must be signed in; a private locator is not anonymous access.",
                )}
              </Alert>
            )}
          </Surface>
          <Surface className="p-5 sm:p-6">
            <h2 className="font-heading text-xl font-bold">
              {t("Participation schedule")}
            </h2>
            <p className="mt-1 text-sm leading-6 text-foreground-muted">
              {t(
                "Times use your local timezone. The end time does not close the assessment or shorten a running attempt.",
              )}
            </p>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <DateTimeField
                error={errors.startAt ? localize(errors.startAt) : undefined}
                label={t("Participation start")}
                onChange={(instant) => set("startAt", instant)}
                required
                value={fields.startAt}
              />
              <DateTimeField
                error={errors.endAt ? localize(errors.endAt) : undefined}
                label={t("Participation end")}
                onChange={(instant) => set("endAt", instant)}
                required
                value={fields.endAt}
              />
            </div>
            <div className="mt-4">
              {initial.durationMinutes === 0 ? (
                <Alert title={t("Existing unlimited duration")}>
                  {t(
                    "This assessment has unlimited duration, which stays unchanged; monitoring cannot be enabled.",
                  )}
                </Alert>
              ) : (
                <TextField
                  error={
                    errors.durationMinutes
                      ? localize(errors.durationMinutes)
                      : undefined
                  }
                  helperText={t(
                    "Whole minutes from 5 to 240. Each attempt's deadline starts when the student begins.",
                  )}
                  label={t("Attempt duration (minutes)")}
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
            <div className="mt-4">
              <TextField
                error={
                  errors.maxAttempts ? localize(errors.maxAttempts) : undefined
                }
                helperText={t(
                  "Use 0 for unlimited attempts, or 1 to 10. This cannot change after opening.",
                )}
                label={t("Maximum Attempts")}
                max={10}
                min={0}
                onChange={(event) => set("maxAttempts", event.target.value)}
                required
                step={1}
                type="number"
                value={fields.maxAttempts}
              />
            </div>
          </Surface>
          {Object.keys(errors).length > 0 ? (
            <Alert title={t("Check the highlighted fields")} variant="danger">
              {t(
                "The configuration was not sent. Review the field messages above.",
              )}
            </Alert>
          ) : null}
        </fieldset>

        {/* Contextual sidebar column */}
        <aside
          aria-label={t("Publication summary")}
          className="sticky top-20 space-y-4 lg:top-24"
        >
          <Surface className="p-5">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-foreground-muted">
              {descriptionLabel}
            </h2>
            <dl className="mt-3 space-y-2 text-sm">
              <div>
                <dt className="text-foreground-muted">{t("Quiz version")}</dt>
                <dd className="font-mono text-xs break-all text-foreground">
                  {initial.versionNumber}
                </dd>
              </div>
              <div>
                <dt className="text-foreground-muted">{t("Audience")}</dt>
                <dd className="font-semibold text-foreground">
                  {fields.audience === "CLASS" ? t("Classroom") : t("Public")}
                </dd>
              </div>
              {fields.audience === "CLASS" && fields.classroomId ? (
                <div>
                  <dt className="text-foreground-muted">{t("Classroom")}</dt>
                  <dd className="font-mono text-xs break-all text-foreground">
                    {fields.classroomId}
                  </dd>
                </div>
              ) : null}
              <div>
                <dt className="text-foreground-muted">{t("Duration")}</dt>
                <dd className="font-semibold text-foreground">
                  {initial.durationMinutes === 0
                    ? t("Unlimited")
                    : `${fields.durationMinutes || "—"} min`}
                </dd>
              </div>
            </dl>
          </Surface>
          <Button
            aria-label={isPending ? t("Saving Publication") : undefined}
            className="w-full"
            disabled={disabled}
            isLoading={isPending}
            loadingLabel={t("Saving Publication")}
            type="submit"
          >
            {mode === "create"
              ? t("Review and create DRAFT")
              : t("Review DRAFT changes")}
          </Button>
        </aside>
      </form>
      {review !== null ? (
        <PublicationConfirmation
          confirmLabel={mode === "create" ? t("Create DRAFT") : t("Save DRAFT")}
          onCancel={() => setReview(null)}
          onConfirm={() => {
            const configuration = review;
            setReview(null);
            onSubmit(configuration);
          }}
          title={
            mode === "create"
              ? t("Review new Publication")
              : t("Review DRAFT changes")
          }
        >
          <PublicationSummary configuration={review} />
          <p>
            {t("This saves a DRAFT only. OPEN is a separate reviewed action.")}
          </p>
        </PublicationConfirmation>
      ) : null}
    </>
  );
}
