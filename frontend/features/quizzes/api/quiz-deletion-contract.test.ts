import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { quizDeletionStatusSchema } from "./quiz-api-client";
import { deletionStageSchema } from "./quiz-deletion-client";

const contract = readFileSync(
  resolve(
    process.cwd(),
    "../services/quiz-service/contracts/quiz-deletion.openapi.yaml",
  ),
  "utf8",
);

describe("quiz deletion OpenAPI parity", () => {
  it("keeps derived accepted states and progress stages aligned with the authoritative contract", () => {
    const accepted =
      contract
        .split("    QuizDeletionRequestAccepted:")[1]
        ?.split("    QuizDeletionStatus:")[0] ?? "";
    const status =
      contract.split("    QuizDeletionStatus:")[1]?.split("    Error:")[0] ??
      "";
    const tokens = (section: string) =>
      [...section.matchAll(/^\s+- ([A-Z_]+)$/gm)].map((match) => match[1]);
    expect(tokens(accepted)).toEqual(quizDeletionStatusSchema.options);
    expect(tokens(status)).toEqual(deletionStageSchema.options);
    expect(contract).toContain(
      "/api/quizzes/{quizId}/deletion-requests/latest:",
    );
    expect(contract).toContain("minLength: 0");
  });
});
