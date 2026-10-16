import type {
  FullResult,
  Reporter,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";

/** Failures can contain DOM/HTTP credentials or private metadata: omit payloads. */
export default class SanitizedReporter implements Reporter {
  onTestEnd(test: TestCase, result: TestResult) {
    process.stdout.write(`${result.status}: ${test.title}\n`);
    for (const annotation of test.annotations) {
      if (annotation.type === "evidence" && annotation.description)
        process.stdout.write(`  ${annotation.description}\n`);
    }
    if (result.errors.length > 0)
      process.stdout.write(
        `  Failure details withheld (${result.errors.length}); inspect locally with credentials excluded.\n`,
      );
    for (const error of result.errors) {
      if (error.message) {
        const lines = error.message.split("\n").slice(0, 6).join("\n    ");
        process.stdout.write(`  Error: ${lines}\n`);
      }
      if (error.location)
        process.stdout.write(
          `  Location: ${error.location.file}:${error.location.line}:${error.location.column}\n`,
        );
    }
  }

  onEnd(result: FullResult) {
    process.stdout.write(`Real W3-A suite: ${result.status}\n`);
  }
}
