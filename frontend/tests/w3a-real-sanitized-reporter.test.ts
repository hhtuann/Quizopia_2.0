import {
  mkdtempSync,
  readFileSync,
  renameSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type {
  FullConfig,
  FullResult,
  Suite,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";
import { afterEach, describe, expect, it, vi } from "vitest";
import SanitizedReporter from "./w3a-real-e2e/sanitized-reporter";

const SOURCE_SHA = "a".repeat(40);

function evidencePath() {
  return join(
    mkdtempSync(join(tmpdir(), "quizopia-w3-evidence-")),
    "run.jsonl",
  );
}

function configure(path: string) {
  vi.stubEnv("W3A_REAL_EVIDENCE_FILE", path);
  vi.stubEnv("W3A_REAL_SOURCE_SHA", SOURCE_SHA);
  vi.stubEnv("W3A_REAL_EVIDENCE_RUN_ID", "w3-test-run");
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("sanitized W3 real reporter", () => {
  it("writes exclusive, private, timestamped JSONL without failure messages", () => {
    const path = evidencePath();
    configure(path);
    const reporter = new SanitizedReporter();
    reporter.onBegin?.({} as FullConfig, {} as Suite);
    reporter.onTestEnd?.(
      {
        id: "journey-1",
        title: "synthetic journey",
        annotations: [{ type: "evidence", description: "HTTP 200 observed" }],
      } as TestCase,
      {
        status: "failed",
        startTime: new Date("2026-10-18T01:00:00Z"),
        duration: 1250,
        errors: [{ message: "secret-bearing failure body must not persist" }],
      } as TestResult,
    );
    reporter.onEnd?.({ status: "failed" } as FullResult);

    const records = readFileSync(path, "utf8")
      .trim()
      .split("\n")
      .map((line) => JSON.parse(line));
    expect(records).toHaveLength(3);
    expect(records[0]).toMatchObject({
      type: "run-start",
      runId: "w3-test-run",
      sourceSha: SOURCE_SHA,
    });
    expect(records[1]).toEqual({
      type: "test-result",
      runId: "w3-test-run",
      sourceSha: SOURCE_SHA,
      testId: "journey-1",
      title: "synthetic journey",
      status: "failed",
      startedAt: "2026-10-18T01:00:00.000Z",
      completedAt: "2026-10-18T01:00:01.250Z",
      durationMillis: 1250,
      evidence: ["HTTP 200 observed"],
      errorCount: 1,
    });
    expect(records[2]).toMatchObject({
      type: "run-end",
      runId: "w3-test-run",
      sourceSha: SOURCE_SHA,
      status: "failed",
    });
    expect(readFileSync(path, "utf8")).not.toContain("secret-bearing");
    expect(statSync(path).mode & 0o777).toBe(0o600);
  });

  it("refuses to overwrite an existing evidence file", () => {
    const path = evidencePath();
    writeFileSync(path, "existing\n", { mode: 0o600 });
    configure(path);

    const reporter = new SanitizedReporter();
    expect(() => reporter.onBegin?.({} as FullConfig, {} as Suite)).toThrow();
    expect(readFileSync(path, "utf8")).toBe("existing\n");
  });

  it("keeps writing to the exclusively opened receipt if its path is replaced", () => {
    const path = evidencePath();
    const retainedPath = `${path}.retained`;
    const replacementTarget = `${path}.replacement`;
    configure(path);

    const reporter = new SanitizedReporter();
    reporter.onBegin?.({} as FullConfig, {} as Suite);
    renameSync(path, retainedPath);
    writeFileSync(replacementTarget, "replacement\n", { mode: 0o600 });
    symlinkSync(replacementTarget, path);

    reporter.onTestEnd?.(
      {
        id: "journey-2",
        title: "replacement-resistant journey",
        annotations: [],
      } as unknown as TestCase,
      {
        status: "passed",
        startTime: new Date("2026-10-18T02:00:00Z"),
        duration: 500,
        errors: [],
      } as unknown as TestResult,
    );
    reporter.onEnd?.({ status: "passed" } as FullResult);

    expect(readFileSync(replacementTarget, "utf8")).toBe("replacement\n");
    const records = readFileSync(retainedPath, "utf8")
      .trim()
      .split("\n")
      .map((line) => JSON.parse(line));
    expect(records).toHaveLength(3);
    expect(records[1]).toMatchObject({
      type: "test-result",
      testId: "journey-2",
      status: "passed",
    });
    expect(records[2]).toMatchObject({ type: "run-end", status: "passed" });
  });

  it("requires exact source and run identity for evidence output", () => {
    const path = evidencePath();
    configure(path);
    vi.stubEnv("W3A_REAL_SOURCE_SHA", "not-a-sha");

    const reporter = new SanitizedReporter();
    expect(() => reporter.onBegin?.({} as FullConfig, {} as Suite)).toThrow(
      /W3A_REAL_SOURCE_SHA/,
    );
  });
});
