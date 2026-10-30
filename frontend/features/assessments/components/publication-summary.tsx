import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
import type { Configuration } from "../api/publication-contract.generated";
import { instantToUtcField } from "../utils/utc-time";

function monitoringLabel(
  choice: Configuration["monitoringChoice"],
  t: ReturnType<typeof useI18n>["t"],
): string {
  return choice === "ENABLED"
    ? t("Enabled: Activity Evidence + LiveKit Camera")
    : choice === "UNCONFIGURED"
      ? t("Not yet configured (DRAFT only)")
      : t("Disabled");
}

export function PublicationSummary({
  configuration,
}: {
  readonly configuration: Configuration;
}) {
  const { t, locale } = useI18n();
  // Identifier rows keep monospace styling regardless of the displayed label.
  const entries: readonly (readonly [
    label: string,
    value: string,
    identifier: boolean,
  ])[] = [
    [t("Title"), configuration.title, false],
    [t("Description"), configuration.description || t("No description"), false],
    [
      t("Source version"),
      t("Version {number}", { number: configuration.versionNumber }),
      false,
    ],
    [t("Quiz ID"), configuration.quizId, true],
    [t("QuizVersion ID"), configuration.quizVersionId, true],
    [t("Audience"), configuration.audience, false],
    [
      t("Classroom ID"),
      configuration.classroomId ?? t("Not applicable (PUBLIC)"),
      false,
    ],
    [
      t("Monitoring"),
      monitoringLabel(configuration.monitoringChoice, t),
      false,
    ],
    [
      t("Attempt duration"),
      configuration.durationMinutes === 0
        ? t("Unlimited (existing configuration)")
        : t("{minutes} minutes", { minutes: configuration.durationMinutes }),
      false,
    ],
    [
      t("Maximum Attempts"),
      configuration.maxAttempts === 0
        ? t("Unlimited")
        : String(configuration.maxAttempts ?? 1),
      false,
    ],
  ];
  return (
    <dl className="grid grid-cols-1 gap-4 text-sm">
      {entries.map(([label, value, identifier]) => (
        <div className="min-w-0" key={label}>
          <dt className="font-semibold text-foreground-muted">{label}</dt>
          <dd
            className={`mt-1 whitespace-pre-wrap break-words text-foreground ${identifier ? "break-all font-mono text-xs" : ""}`}
          >
            {value}
          </dd>
        </div>
      ))}
      {(["startAt", "endAt"] as const).map((field) => (
        <div className="min-w-0" key={field}>
          <dt className="font-semibold text-foreground-muted">
            {field === "startAt"
              ? t("Participation start")
              : t("Participation end")}
          </dt>
          <dd className="mt-1 text-foreground">
            <time dateTime={`${instantToUtcField(configuration[field])}Z`}>
              {formatInstant(locale, configuration[field], {
                withTimeZone: true,
              })}
            </time>
          </dd>
        </div>
      ))}
    </dl>
  );
}
