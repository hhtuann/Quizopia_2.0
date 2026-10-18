import { closeSync, openSync, writeSync } from "node:fs";
import { resolve } from "node:path";
import type {
  FullConfig,
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";

/** Failures can contain DOM/HTTP credentials or private metadata: omit payloads. */
export default class SanitizedReporter implements Reporter {
  private readonly evidenceFile = process.env.W3A_REAL_EVIDENCE_FILE
    ? resolve(process.env.W3A_REAL_EVIDENCE_FILE)
    : undefined;
  private readonly sourceSha = process.env.W3A_REAL_SOURCE_SHA;
  private readonly runId = process.env.W3A_REAL_EVIDENCE_RUN_ID;
  private evidenceFd: number | undefined;

  private appendEvidence(record: Record<string, unknown>) {
    if (this.evidenceFd === undefined) return;
    writeSync(this.evidenceFd, `${JSON.stringify(record)}\n`, null, "utf8");
  }

  onBegin(_config: FullConfig, _suite: Suite) {
    if (!this.evidenceFile) return;
    if (!this.sourceSha?.match(/^[0-9a-f]{40}$/))
      throw new Error(
        "W3A_REAL_SOURCE_SHA must be an exact lowercase 40-character Git SHA when evidence output is enabled",
      );
    if (!this.runId?.match(/^[a-z0-9][a-z0-9-]{2,63}$/))
      throw new Error(
        "W3A_REAL_EVIDENCE_RUN_ID must be a safe 3-64 character identifier when evidence output is enabled",
      );
    this.evidenceFd = openSync(this.evidenceFile, "wx", 0o600);
    try {
      writeSync(
        this.evidenceFd,
        `${JSON.stringify({
          type: "run-start",
          runId: this.runId,
          sourceSha: this.sourceSha,
          startedAt: new Date().toISOString(),
        })}\n`,
        null,
        "utf8",
      );
    } catch (error) {
      closeSync(this.evidenceFd);
      this.evidenceFd = undefined;
      throw error;
    }
  }

  onTestEnd(test: TestCase, result: TestResult) {
    process.stdout.write(`${result.status}: ${test.title}\n`);
    const evidence = test.annotations
      .filter(
        (annotation) =>
          annotation.type === "evidence" && annotation.description,
      )
      .map((annotation) => annotation.description as string);
    for (const annotation of test.annotations) {
      if (annotation.type === "evidence" && annotation.description)
        process.stdout.write(`  ${annotation.description}\n`);
    }
    if (result.errors.length > 0)
      process.stdout.write(
        `  Failure details withheld (${result.errors.length}); inspect locally with credentials excluded.\n`,
      );
    for (const error of result.errors) {
      if (error.location)
        process.stdout.write(
          `  Location: ${error.location.file}:${error.location.line}:${error.location.column}\n`,
        );
    }
    this.appendEvidence({
      type: "test-result",
      runId: this.runId,
      sourceSha: this.sourceSha,
      testId: test.id,
      title: test.title,
      status: result.status,
      startedAt: result.startTime.toISOString(),
      completedAt: new Date(
        result.startTime.getTime() + result.duration,
      ).toISOString(),
      durationMillis: result.duration,
      evidence,
      errorCount: result.errors.length,
    });
  }

  onEnd(result: FullResult) {
    process.stdout.write(`Real W3-A suite: ${result.status}\n`);
    try {
      this.appendEvidence({
        type: "run-end",
        runId: this.runId,
        sourceSha: this.sourceSha,
        status: result.status,
        completedAt: new Date().toISOString(),
      });
    } finally {
      if (this.evidenceFd !== undefined) {
        closeSync(this.evidenceFd);
        this.evidenceFd = undefined;
      }
    }
  }
}
