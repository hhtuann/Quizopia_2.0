"use client";

import { formatInstant } from "../../../lib/i18n/core";
import { useI18n } from "../../../lib/i18n/provider";
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
  const { t, localize, locale } = useI18n();
  if (items.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-border-strong p-6 text-sm text-foreground-secondary">
        {t("No monitoring events yet for this session.")}
      </div>
    );
  }
  return (
    <ol className="space-y-3" aria-label={t("Monitoring activity timeline")}>
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
              {localize(describeEvidenceType(item.type))}
            </p>
            <p className="mt-1 text-sm text-foreground-secondary">
              {localize(PROVENANCE_LABELS[item.source])}
              {item.questionNumber === null
                ? ""
                : ` · ${t("Question {number}", { number: item.questionNumber })}`}
              {item.assessmentRevision === null
                ? ""
                : ` · ${t("Assessment revision {revision}", {
                    revision: item.assessmentRevision,
                  })}`}
            </p>
            <p className="mt-1 text-xs text-foreground-muted">
              {t(
                "Observations can be incomplete or manipulated, and saved facts only show what was stored — neither proves misconduct.",
              )}
            </p>
          </div>
          <div className="space-y-1 text-xs text-foreground-muted sm:text-right">
            <p>
              {item.observedAt === null
                ? t("Observed: {value}", { value: t("not reported") })
                : t("Observed: {value}", {
                    value: formatInstant(locale, item.observedAt),
                  })}
            </p>
            <p>
              {t("Received: {value}", {
                value: formatInstant(locale, item.receivedAt),
              })}
            </p>
          </div>
        </li>
      ))}
    </ol>
  );
}
