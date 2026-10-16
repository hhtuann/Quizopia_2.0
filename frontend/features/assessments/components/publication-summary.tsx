import type { Configuration } from "../api/publication-contract.generated";
import { formatUtc, instantToUtcField } from "../utils/utc-time";

export function PublicationSummary({
  configuration,
}: {
  readonly configuration: Configuration;
}) {
  const entries = [
    ["Title", configuration.title],
    ["Description", configuration.description || "No description"],
    ["Immutable source", `Version ${configuration.versionNumber}`],
    ["Quiz ID", configuration.quizId],
    ["QuizVersion ID", configuration.quizVersionId],
    ["Audience", configuration.audience],
    ["Classroom ID", configuration.classroomId ?? "Not applicable (PUBLIC)"],
    [
      "Monitoring",
      configuration.monitoringChoice === "ENABLED"
        ? "Enabled: Activity Evidence + LiveKit Camera"
        : configuration.monitoringChoice === "UNCONFIGURED"
          ? "Not yet configured (DRAFT only)"
          : "Disabled",
    ],
    [
      "Attempt duration",
      configuration.durationMinutes === 0
        ? "Unlimited (existing configuration)"
        : `${configuration.durationMinutes} minutes`,
    ],
    [
      "Maximum Attempts",
      configuration.maxAttempts === 0
        ? "Unlimited"
        : String(configuration.maxAttempts ?? 1),
    ],
  ];
  return (
    <dl className="grid gap-x-5 gap-y-4 text-sm sm:grid-cols-2">
      {entries.map(([label, value]) => (
        <div className="min-w-0" key={label}>
          <dt className="font-semibold text-foreground-muted">{label}</dt>
          <dd
            className={`mt-1 whitespace-pre-wrap break-words text-foreground ${label?.endsWith("ID") ? "break-all font-mono text-xs" : ""}`}
          >
            {value}
          </dd>
        </div>
      ))}
      {(["startAt", "endAt"] as const).map((field) => (
        <div className="min-w-0" key={field}>
          <dt className="font-semibold text-foreground-muted">
            Participation {field === "startAt" ? "start" : "end"}
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
