import { describeEvidenceType } from "../api/monitoring-api-client";
import {
  PROVENANCE_LABELS,
  type MonitoringEvidence,
} from "../model/monitoring";

export function TeacherTimeline({
  items,
}: {
  readonly items: readonly MonitoringEvidence[];
}) {
  if (items.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-border-strong p-6 text-sm text-foreground-secondary">
        No monitoring evidence is available for this authorized session yet.
      </div>
    );
  }
  return (
    <ol className="space-y-3" aria-label="Monitoring activity timeline">
      {items.map((item) => (
        <li
          className="grid gap-2 rounded-xl border border-border bg-surface p-4 sm:grid-cols-[8rem_minmax(0,1fr)_11rem]"
          key={item.ordinal}
        >
          <div>
            <span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-semibold text-primary">
              #{item.ordinal}
            </span>
          </div>
          <div>
            <p className="font-semibold text-foreground">
              {describeEvidenceType(item.type)}
            </p>
            <p className="mt-1 text-sm text-foreground-secondary">
              {PROVENANCE_LABELS[item.source]}
              {item.questionNumber === null
                ? ""
                : ` · Question ${item.questionNumber}`}
              {item.assessmentRevision === null
                ? ""
                : ` · Assessment revision ${item.assessmentRevision}`}
            </p>
            <p className="mt-1 text-xs text-foreground-muted">
              Browser observations can be incomplete or manipulated. Persistence
              facts mean Assessment stored metadata; neither proves misconduct.
            </p>
          </div>
          <div className="space-y-1 text-xs text-foreground-muted sm:text-right">
            <p>
              Observed/occurred:{" "}
              {item.observedAt === null
                ? "not reported"
                : formatInstant(item.observedAt)}
            </p>
            <p>Received: {formatInstant(item.receivedAt)}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

function formatInstant(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(value));
}
