import { expect, test, type Page } from "@playwright/test";
import type {
  ClosureView,
  Notification,
  OwnerView,
} from "../../features/assessments/api/publication-contract.generated";

// Browser contract tests: production UI/executor, intercepted Gateway responses.
// They do not establish live Identity/Assessment/Classroom compatibility.
// English UI strings require the locale cookie because the app defaults to vi.
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});
const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const closureRequestId = "17b14962-3a2e-4d2d-926a-3b36ea90c198";
const notificationId = "27b14962-3a2e-4d2d-926a-3b36ea90c197";
const attemptId = "37b14962-3a2e-4d2d-926a-3b36ea90c196";
const studentId = "57b14962-3a2e-4d2d-926a-3b36ea90c194";
const teacherId = "67b14962-3a2e-4d2d-926a-3b36ea90c193";
const resultId = "77b14962-3a2e-4d2d-926a-3b36ea90c192";

async function mockAuth(page: Page, roles: string[]) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "w3b3-browser-token",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        id: roles.includes("TEACHER") ? teacherId : studentId,
        username: roles.includes("TEACHER") ? "teacher" : "student",
        email: "actor@gmail.com",
        roles,
      }),
    }),
  );
}

const ownerView: OwnerView = {
  publicationId,
  configuration: {
    title: "Closure protocol assessment",
    description: "Durable closure browser fixture",
    quizId: "87b14962-3a2e-4d2d-926a-3b36ea90c191",
    quizVersionId: "97b14962-3a2e-4d2d-926a-3b36ea90c190",
    versionNumber: 2,
    audience: "CLASS",
    classroomId: "a7b14962-3a2e-4d2d-926a-3b36ea90c189",
    startAt: "2026-10-13T08:00:00.000000Z",
    endAt: "2026-10-14T08:00:00.000000Z",
    durationMinutes: 0,
    monitoringChoice: "DISABLED",
    maxAttempts: 1,
  },
  state: "OPEN",
  revision: 7,
  createdAt: "2026-10-12T08:00:00.000000Z",
  openedAt: "2026-10-13T07:00:00.000000Z",
  privateLocator: null,
};

const pendingView: ClosureView = {
  closureRequestId,
  publicationId,
  requestingTeacherId: teacherId,
  requestedAt: "2026-10-14T09:00:00.000000Z",
  state: "PENDING",
  finalizationStartedAt: null,
  resolvedAt: null,
  publicationRevision: 8,
};

test("Teacher requests delayed closure and sees bounded completion without a false CLOSED claim", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  let closureView = pendingView;
  const commands: Array<{
    path: string;
    body: unknown;
    key: string | undefined;
  }> = [];

  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(ownerView),
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/closure-requests**`,
    async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      expect(request.headers().authorization).toBe("Bearer w3b3-browser-token");
      if (request.method() === "GET") {
        await route.fulfill({
          contentType: "application/json",
          status: 200,
          body: JSON.stringify(closureView),
        });
        return;
      }
      const body = request.postDataJSON();
      const key = request.headers()["idempotency-key"];
      commands.push({ path, body, key });
      expect(body).toEqual({
        expectedRevision: path.endsWith("/complete") ? 8 : 7,
      });
      expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
      if (path.endsWith("/complete")) {
        closureView = {
          ...closureView,
          state: "FINALIZING",
          finalizationStartedAt: "2026-10-16T08:00:00.000000Z",
          publicationRevision: 9,
        };
        await route.fulfill({
          contentType: "application/json",
          status: 202,
          body: JSON.stringify({
            closureRequestId,
            publicationId,
            closed: false,
            publicationRevision: 9,
          }),
        });
        return;
      }
      await route.fulfill({
        contentType: "application/json",
        status: 201,
        body: JSON.stringify(pendingView),
      });
    },
  );

  await page.goto(`/app/assessments/${publicationId}/closure`);
  await expect(page.getByText("Revision 7")).toBeVisible();
  await expect(
    page.getByRole("link", { name: /direct CLOSE/ }),
  ).toHaveAttribute("href", `/app/assessments/${publicationId}`);
  await page
    .getByRole("button", { name: "Review and REQUEST closure" })
    .click();
  await page.getByLabel("Type REQUEST to enable this action").fill("REQUEST");
  await page.getByRole("button", { name: "REQUEST delayed closure" }).click();
  await expect(
    page.getByRole("heading", { name: "Closure request created" }),
  ).toBeVisible();
  await expect(page.getByText(/does not close the assessment/i)).toBeVisible();
  await expect(
    page.getByText("Publication CLOSED", { exact: true }),
  ).toHaveCount(0);

  await page.getByRole("link", { name: "Open closure request" }).click();
  await expect(page).toHaveURL(
    `/app/assessments/${publicationId}/closure/${closureRequestId}`,
  );
  await expect(page.getByText(closureRequestId, { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Review and COMPLETE closure" })
    .click();
  const completionDialog = page.getByRole("dialog", {
    name: "Confirm completion command",
  });
  await completionDialog
    .getByLabel("Type COMPLETE to enable this action")
    .fill("COMPLETE");
  await completionDialog
    .getByRole("button", { name: "COMPLETE closure", exact: true })
    .click();
  await expect(page.getByText("Progress saved")).toBeVisible();
  await expect(page.getByText("Finalizing", { exact: true })).toBeVisible();
  await expect(
    page.getByText("Publication CLOSED", { exact: true }),
  ).toHaveCount(0);

  expect(commands).toHaveLength(2);
  expect(commands[0]?.path).toBe(
    `/api/assessments/publications/${publicationId}/closure-requests`,
  );
  expect(commands[1]?.path).toBe(
    `/api/assessments/publications/${publicationId}/closure-requests/${closureRequestId}/complete`,
  );
  expect(commands[0]?.key).not.toBe(commands[1]?.key);
});

test("Teacher results dashboard preserves exact totals, real IDs and an opaque cursor", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  const cursor = "cGFnZTI.c2ln";
  const queries: Array<{ raw: string; cursor: string | null }> = [];
  const studentId = "57b14962-3a2e-4d2d-926a-3b36ea90c194";
  const submittedAttemptId = "37b14962-3a2e-4d2d-926a-3b36ea90c196";
  const abandonedAttemptId = "47b14962-3a2e-4d2d-926a-3b36ea90c195";
  const result = {
    resultId,
    attemptId: submittedAttemptId,
    publicationId,
    ordinal: "9007199254740993",
    earnedWeight: "12345678901234567890.125",
    possibleWeight: "12345678901234567891",
    percentageDisplay: "100.00",
    gradingPolicyVersion: 1,
    gradingPolicyId: "QUIZOPIA_GRADING_V1",
    terminalReason: "TEACHER_NOTICE_CUTOFF",
    finalizedAt: "2026-10-16T09:00:00.000000Z",
  };
  const studentRow = {
    studentId,
    studentName: null,
    attemptCount: "2",
    gradedAttemptCount: "1",
    hasActive: false,
    hasSubmitted: true,
    latestAttempt: {
      attemptId: abandonedAttemptId,
      ordinal: "2",
      state: "ABANDONED",
      startedAt: "2026-10-16T10:00:00.000000Z",
      deadlineAt: null,
      finalizedAt: "2026-10-16T10:05:00.000000Z",
      terminalReason: "STUDENT_ABANDON",
      result: null,
      teacherReviewAvailable: false,
    },
    latestGradedResult: result,
    bestResult: result,
    averageResult: {
      sumEarnedWeight: "12345678901234567890.125",
      sumPossibleWeight: "12345678901234567891",
      gradedAttemptCount: "1",
      scoreDisplay: "10.00",
    },
  };
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(ownerView),
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/student-overview`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          asOf: "2026-10-16T11:00:00.000000Z",
          activeStudentCount: "0",
          submittedStudentCount: "1",
          participatedStudentCount: "1",
          gradedStudentCount: "1",
          bestScore: {
            earnedWeight: "12345678901234567890.125",
            possibleWeight: "12345678901234567891",
            scoreDisplay: "10.00",
          },
          averageBestScore: {
            sumEarnedWeight: "12345678901234567890.125",
            sumPossibleWeight: "12345678901234567891",
            gradedAttemptCount: "1",
            scoreDisplay: "10.00",
          },
          scoreBins: Array.from({ length: 20 }, (_, index) => ({
            index,
            lowerBound: (index / 2).toString(),
            upperBound: ((index + 1) / 2).toString(),
            lowerInclusive: index === 0,
            upperInclusive: true,
            count: index === 19 ? "1" : "0",
          })),
        }),
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/students**`,
    async (route) => {
      const url = new URL(route.request().url());
      queries.push({
        raw: url.search,
        cursor: url.searchParams.get("cursor"),
      });
      expect(
        [...url.searchParams.keys()].every((key) =>
          [
            "cursor",
            "limit",
            "status",
            "sort",
            "direction",
            "studentId",
          ].includes(key),
        ),
      ).toBe(true);
      const next = url.searchParams.get("cursor");
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(
          next === null
            ? {
                publicationId,
                asOf: "2026-10-16T11:00:00.000000Z",
                items: [studentRow],
                nextCursor: cursor,
              }
            : {
                publicationId,
                asOf: "2026-10-16T11:00:01.000000Z",
                items: [],
                nextCursor: null,
              },
        ),
      });
    },
  );

  await page.goto(`/app/assessments/${publicationId}/results`);
  // Distinct counters render verbatim with the overlap explanation.
  await expect(page.getByText("Total participants")).toBeVisible();
  await expect(
    page.getByText(/Groups are never added together/i),
  ).toBeVisible();
  // Exact best score out of ten and the populated 20th bin.
  await expect(page.getByText("10.00 / 10").first()).toBeVisible();
  const tableRegion = page.getByRole("region", {
    name: "Score distribution table",
  });
  await expect(tableRegion).toBeVisible();
  await expect(
    tableRegion.getByRole("row", { name: /9\.5 – 10\s*1/ }),
  ).toBeVisible();
  // The student directory row keeps the real ID; no answers or keys appear.
  await expect(
    page.getByText(studentId, { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByText(/correct answer/i)).toHaveCount(0);
  const directoryRegion = page.getByRole("region", {
    name: "Student directory",
  });
  await directoryRegion.focus();
  await expect(directoryRegion).toBeFocused();
  // Attempt history uses its own server pagination; review stays gated to
  // graded SUBMITTED attempts, so the abandoned latest row offers no link.
  await page.route(
    `**/api/assessments/publications/${publicationId}/students/${studentId}/attempts**`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          studentId,
          asOf: "2026-10-16T11:02:00.000000Z",
          items: [studentRow.latestAttempt],
          nextCursor: null,
        }),
      }),
  );
  await page.getByRole("button", { name: "View attempts" }).click();
  await expect(page.getByText("No result (ungraded)").first()).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Review submitted work" }),
  ).toHaveCount(0);
  const initialQueries = queries.filter((query) => query.cursor === null);
  expect(initialQueries.length).toBeGreaterThanOrEqual(1);
});

test("Student inbox distinguishes preparation from a confirmed recipient cutoff at mobile width", async ({
  page,
}) => {
  await mockAuth(page, ["STUDENT"]);
  await page.setViewportSize({ width: 375, height: 812 });
  let classroomRequests = 0;
  await page.route("**/api/classrooms/**", async (route) => {
    classroomRequests++;
    await route.abort();
  });
  const preparing: Notification = {
    notificationId,
    publicationId,
    publicationTitle: "Closure protocol assessment",
    attemptId,
    closureRequestId,
    teacherInitiated: true,
    contentVersion: 1,
    publishedAt: "2026-10-14T09:00:00.000000Z",
    confirmedNoticeAt: null,
    closureCutoffAt: null,
    warningHours: 48,
    noticeStatus: "PREPARING",
    message: "Your durable notice is still being prepared.",
    resultId: null,
    serverNow: "2026-10-14T09:00:01.000000Z",
  };
  const confirmed: Notification = {
    ...preparing,
    confirmedNoticeAt: "2026-10-14T09:30:00.123456Z",
    closureCutoffAt: "2026-10-16T09:30:00.123456Z",
    noticeStatus: "NOTICE_CONFIRMED",
    message: "Your confirmed notice is accessible.",
    serverNow: "2026-10-14T10:30:00.123456Z",
  };
  await page.route("**/api/assessments/notifications", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({ items: [preparing], nextCursor: null }),
    }),
  );
  await page.route(
    `**/api/assessments/notifications/${notificationId}`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(confirmed),
      }),
  );

  await page.goto("/app/notifications");
  await expect(
    page.getByText("Notice preparing", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Waiting for notice confirmation")).toBeVisible();
  await expect(page.getByText(/acknowledge/i)).toHaveCount(0);
  await page.getByRole("link", { name: "View notice" }).click();
  await expect(
    page.getByText("48-hour period confirmed", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("14 Oct 2026, 16:30 (Vietnam time, UTC+7)"),
  ).toBeVisible();
  await expect(
    page.getByText("16 Oct 2026, 16:30 (Vietnam time, UTC+7)"),
  ).toBeVisible();
  await expect(page.getByText(attemptId, { exact: true })).toBeVisible();
  expect(classroomRequests).toBe(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("current Student authority gates the inbox before any recipient request", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  let notificationRequests = 0;
  await page.route("**/api/assessments/notifications**", async (route) => {
    notificationRequests++;
    await route.abort();
  });
  await page.goto("/app/notifications");
  await expect(
    page.getByText("Student access required", { exact: true }),
  ).toBeVisible();
  expect(notificationRequests).toBe(0);
});
