import { useI18n } from "../../../lib/i18n/provider";
import type { Configuration } from "../api/publication-contract.generated";
import { formatUtc, instantToUtcField } from "../utils/utc-time";

export function PublicationSummary({
  configuration,
}: {
  readonly configuration: Configuration;
}) {
  const { t } = useI18n();
  // Identifier rows keep monospace styling regardless of the displayed label.
  const entries: readonly (readonly [
    label: string,
    value: string,
    identifier: boolean,
  ])[] = [
    [t("Title"), configuration.title, false],
    [t("Description"), configuration.description || t("No description"), false],
    [
      t("Immutable source"),
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
      configuration.monitoringChoice === "ENABLED"
        ? t("Enabled: Activity Evidence + LiveKit Camera")
        : configuration.monitoringChoice === "UNCONFIGURED"
          ? t("Not yet configured (DRAFT only)")
          : t("Disabled"),
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
    <dl className="grid gap-x-5 gap-y-4 text-sm sm:grid-cols-2">
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
              {formatUtc(configuration[field])}
            </time>
            <span className="mt-1 block break-all font-mono text-xs text-foreground-muted">
              {configuration[field]}
            </span>
          </dd>
        </div>
      ))}
    </dl>
  );
}
