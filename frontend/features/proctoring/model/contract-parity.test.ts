import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { BROWSER_EVENT_TYPES, EVIDENCE_EVENT_TYPES } from "./monitoring";

const monitoringContract = readFileSync(
  resolve(
    process.cwd(),
    "../services/proctoring-service/contracts/monitoring.openapi.yaml",
  ),
  "utf8",
);
const mediaContract = readFileSync(
  resolve(
    process.cwd(),
    "../services/proctoring-service/contracts/media.openapi.yaml",
  ),
  "utf8",
);

describe("implemented OpenAPI parity guard", () => {
  it("covers the exact six monitoring and four media operations", () => {
    const monitoringPaths = [
      "/api/proctoring/monitoring/sessions:",
      "/api/proctoring/monitoring/sessions/by-attempt/{attemptId}:",
      "/api/proctoring/monitoring/sessions/{id}:",
      "/api/proctoring/monitoring/sessions/{id}/events:",
      "/api/proctoring/monitoring/sessions/{id}/reconcile:",
    ];
    for (const path of monitoringPaths)
      expect(monitoringContract).toContain(path);
    expect(monitoringContract.match(/^    (get|post):$/gm) ?? []).toHaveLength(
      6,
    );
    expect(mediaContract.match(/^    (get|post):$/gm) ?? []).toHaveLength(4);
  });

  it("keeps browser and timeline type inventories aligned without making server facts emit-capable", () => {
    for (const type of BROWSER_EVENT_TYPES) {
      expect(monitoringContract).toContain(`- ${type}`);
    }
    expect(EVIDENCE_EVENT_TYPES).toContain("ANSWER_PERSISTED");
    expect(BROWSER_EVENT_TYPES).not.toContain("ANSWER_PERSISTED" as never);
    expect(BROWSER_EVENT_TYPES).not.toContain("SESSION_STARTED" as never);
    expect(BROWSER_EVENT_TYPES).not.toContain("SESSION_ENDED" as never);
  });
});
