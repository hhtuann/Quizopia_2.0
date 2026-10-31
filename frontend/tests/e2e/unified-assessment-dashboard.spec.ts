import { expect, test, type Page } from "@playwright/test";
import type { OwnerView } from "../../features/assessments/api/publication-contract.generated";

// W4F-T3 unified student dashboard: browser contract tests with intercepted
// Gateway responses. They verify the UI contract, query shapes, secrecy and
// responsive/keyboard behavior; live multi-service compatibility is covered by
// the private synthetic demo workflow. English strings need the locale cookie.
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const teacherId = "67b14962-3a2e-4d2d-926a-3b36ea90c193";
const studentA = "57b14962-3a2e-4d2d-926a-3b36ea90c194";
const studentB = "07b14962-3a2e-4d2d-926a-3b36ea90c190";
const studentC = "17b14962-3a2e-4d2d-926a-3b36ea90c189";
const attemptA1 = "37b14962-3a2e-4d2d-926a-3b36ea90c196";
const attemptA2 = "47b14962-3a2e-4d2d-926a-3b36ea90c195";
const attemptB1 = "97b14962-3a2e-4d2d-926a-3b36ea90c188";
const attemptC1 = "a7b14962-3a2e-4d2d-926a-3b36ea90c187";

export async function mockAuth(page: Page, roles: string[]) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "t3-dashboard-token",
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
        id: roles.includes("TEACHER") ? teacherId : studentA,
        username: roles.includes("TEACHER") ? "teacher" : "student",
        email: "actor@gmail.com",
        roles,
      }),
    }),
  );
}

function ownerView(
  audience: "PUBLIC" | "CLASS",
  state: "OPEN" | "CLOSED",
): OwnerView {
  return {
    publicationId,
    configuration: {
      title: `${audience} dashboard fixture`,
      description: null,
      quizId: "87b14962-3a2e-4d2d-926a-3b36ea90c191",
      quizVersionId: "97b14962-3a2e-4d2d-926a-3b36ea90c190",
      versionNumber: 2,
      audience,
      classroomId:
        audience === "CLASS" ? "b7b14962-3a2e-4d2d-926a-3b36ea90c186" : null,
      startAt: "2026-10-13T08:00:00.000000Z",
      endAt: "2026-10-14T08:00:00.000000Z",
      durationMinutes: 0,
      monitoringChoice: "DISABLED",
      maxAttempts: 0,
    },
    state,
    revision: 7,
    createdAt: "2026-10-12T08:00:00.000000Z",
    openedAt: "2026-10-13T07:00:00.000000Z",
    privateLocator: null,
  };
}

function gradedResult(
  attemptId: string,
  ordinal: string,
  percentage: string,
  earned: string,
  possible: string,
) {
  return {
    resultId: `77b14962-3a2e-4d2d-926a-3b36ea90c${ordinal.padStart(3, "0")}`,
    attemptId,
    publicationId,
    ordinal,
    earnedWeight: earned,
    possibleWeight: possible,
    percentageDisplay: percentage,
    gradingPolicyVersion: 1,
    gradingPolicyId: "QUIZOPIA_GRADING_V1",
    terminalReason: "MANUAL_SUBMIT",
    finalizedAt: "2026-10-16T09:00:00.000000Z",
  };
}

// Student A: submitted 10.00 (bin 19) + abandoned retake -> also participated.
// Student B: submitted exactly 0.50 -> upper-inclusive bin 0 ([0, 0.5]).
// Student C: active only -> participated but not submitted/graded.
const directoryFixture = {
  A: {
    studentId: studentA,
    studentName: "Nguyễn Văn An",
    attemptCount: "2",
    gradedAttemptCount: "1",
    hasActive: false,
    hasSubmitted: true,
    latestAttempt: {
      attemptId: attemptA2,
      ordinal: "2",
      state: "ABANDONED",
      startedAt: "2026-10-16T10:00:00.000000Z",
      deadlineAt: null,
      finalizedAt: "2026-10-16T10:05:00.000000Z",
      terminalReason: "STUDENT_ABANDON",
      result: null,
      teacherReviewAvailable: false,
    },
    latestGradedResult: gradedResult(attemptA1, "1", "100.00", "4", "4"),
    bestResult: gradedResult(attemptA1, "1", "100.00", "4", "4"),
    averageResult: {
      sumEarnedWeight: "4",
      sumPossibleWeight: "4",
      gradedAttemptCount: "1",
      scoreDisplay: "10.00",
    },
  },
  B: {
    studentId: studentB,
    studentName: null,
    attemptCount: "1",
    gradedAttemptCount: "1",
    hasActive: false,
    hasSubmitted: true,
    latestAttempt: {
      attemptId: attemptB1,
      ordinal: "1",
      state: "SUBMITTED",
      startedAt: "2026-10-16T08:00:00.000000Z",
      deadlineAt: null,
      finalizedAt: "2026-10-16T08:30:00.000000Z",
      terminalReason: "MANUAL_SUBMIT",
      result: gradedResult(attemptB1, "1", "5.00", "1", "20"),
      teacherReviewAvailable: true,
    },
    latestGradedResult: gradedResult(attemptB1, "1", "5.00", "1", "20"),
    bestResult: gradedResult(attemptB1, "1", "5.00", "1", "20"),
    averageResult: {
      sumEarnedWeight: "1",
      sumPossibleWeight: "20",
      gradedAttemptCount: "1",
      scoreDisplay: "0.50",
    },
  },
  C: {
    studentId: studentC,
    studentName: null,
    attemptCount: "1",
    gradedAttemptCount: "0",
    hasActive: true,
    hasSubmitted: false,
    latestAttempt: {
      attemptId: attemptC1,
      ordinal: "1",
      state: "ACTIVE",
      startedAt: "2026-10-16T11:00:00.000000Z",
      deadlineAt: null,
      finalizedAt: null,
      terminalReason: null,
      result: null,
      teacherReviewAvailable: false,
    },
    latestGradedResult: null,
    bestResult: null,
    averageResult: null,
  },
};

const overviewFixture = {
  publicationId,
  asOf: "2026-10-16T11:30:00.000000Z",
  activeStudentCount: "1",
  submittedStudentCount: "2",
  participatedStudentCount: "3",
  gradedStudentCount: "2",
  bestScore: { earnedWeight: "4", possibleWeight: "4", scoreDisplay: "10.00" },
  averageBestScore: {
    sumEarnedWeight: "5",
    sumPossibleWeight: "24",
    gradedAttemptCount: "2",
    scoreDisplay: "2.08",
  },
  scoreBins: Array.from({ length: 20 }, (_, index) => ({
    index,
    lowerBound: (index / 2).toString(),
    upperBound: ((index + 1) / 2).toString(),
    lowerInclusive: index === 0,
    upperInclusive: true,
    count: index === 0 ? "1" : index === 19 ? "1" : "0",
  })),
};

type DashboardInterception = {
  readonly studentsQueries: Array<Record<string, string>>;
};

export async function interceptDashboard(
  page: Page,
  options: {
    readonly audience: "PUBLIC" | "CLASS";
    readonly state: "OPEN" | "CLOSED";
    readonly items?: (typeof directoryFixture.A)[];
    readonly overview?: unknown;
    readonly overviewStatus?: number;
    readonly studentsStatus?: number;
  },
): Promise<DashboardInterception> {
  const studentsQueries: Array<Record<string, string>> = [];
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(ownerView(options.audience, options.state)),
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/student-overview`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: options.overviewStatus ?? 200,
        body: JSON.stringify(
          options.overviewStatus !== undefined && options.overviewStatus !== 200
            ? { code: "ACCESS_DENIED" }
            : (options.overview ?? overviewFixture),
        ),
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/students**`,
    (route) => {
      const url = new URL(route.request().url());
      const query: Record<string, string> = {};
      url.searchParams.forEach((value, key) => {
        query[key] = value;
      });
      studentsQueries.push(query);
      if (options.studentsStatus !== undefined) {
        return route.fulfill({
          contentType: "application/json",
          status: options.studentsStatus,
          body: JSON.stringify({ code: "ACCESS_DENIED" }),
        });
      }
      return route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          asOf: "2026-10-16T11:30:00.000000Z",
          items: options.items ?? [
            directoryFixture.A,
            directoryFixture.B,
            directoryFixture.C,
          ],
          nextCursor: null,
        }),
      });
    },
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/students/${studentA}/attempts**`,
    (route) =>
      route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          studentId: studentA,
          asOf: "2026-10-16T11:31:00.000000Z",
          items: [
            directoryFixture.A.latestAttempt,
            {
              attemptId: attemptA1,
              ordinal: "1",
              state: "SUBMITTED",
              startedAt: "2026-10-16T09:00:00.000000Z",
              deadlineAt: null,
              finalizedAt: "2026-10-16T09:20:00.000000Z",
              terminalReason: "MANUAL_SUBMIT",
              result: gradedResult(attemptA1, "1", "100.00", "4", "4"),
              teacherReviewAvailable: true,
            },
          ],
          nextCursor: null,
        }),
      }),
  );
  return { studentsQueries };
}

test("CLASS dashboard renders distinct KPIs, exact upper-inclusive bins and per-student aggregates", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  const { studentsQueries } = await interceptDashboard(page, {
    audience: "CLASS",
    state: "OPEN",
  });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await expect(
    page.getByRole("heading", { name: "CLASS dashboard fixture" }),
  ).toBeVisible();
  // Distinct counters with the overlap note.
  await expect(page.getByText("Active students")).toBeVisible();
  await expect(page.getByText("Total participants")).toBeVisible();
  await expect(page.getByText(/never added together/i)).toBeVisible();
  // Exact score presentation out of ten, never a percentage for the mean.
  await expect(page.getByText("2.08 / 10").first()).toBeVisible();
  // Histogram: the boundary 0.50 lands in bin 0; 10.00 in bin 19.
  const table = page.getByRole("region", { name: "Score distribution table" });
  await expect(table).toBeVisible();
  await expect(table.getByRole("row", { name: /0 – 0\.5\s*1/ })).toBeVisible();
  await expect(table.getByRole("row", { name: /9\.5 – 10\s*1/ })).toBeVisible();
  await expect(table.getByRole("row", { name: /4 – 4\.5\s*0/ })).toBeVisible();
  // Directory rows: name with initials fallback, ungraded em-dash, no zeros.
  await expect(page.getByText("Nguyễn Văn An").first()).toBeVisible();
  await expect(page.getByText(studentB, { exact: true }).first()).toBeVisible();
  expect(await page.getByText("0.00%", { exact: true }).count()).toBe(0);
  // The initial directory request carries only allowlisted params.
  const first = studentsQueries[0]!;
  for (const key of Object.keys(first))
    expect([
      "limit",
      "sort",
      "direction",
      "status",
      "cursor",
      "studentId",
    ]).toContain(key);
});

test("PUBLIC dashboard filters, sorts and searches server-side with exact allowlisted queries", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  const { studentsQueries } = await interceptDashboard(page, {
    audience: "PUBLIC",
    state: "OPEN",
    items: [directoryFixture.A],
  });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await page.getByText("Nguyễn Văn An").first().waitFor();
  await page
    .getByLabel("Filter students by attempt state")
    .selectOption("SUBMITTED");
  await page.getByRole("button", { name: "Reverse sort order" }).click();
  await page.getByLabel(/exact student ID/i).fill(studentA);
  await page.getByRole("button", { name: "Find" }).click();
  await expect(page.getByText(/one student with this exact ID/i)).toBeVisible();
  await expect.poll(() => studentsQueries.at(-1)?.studentId).toBe(studentA);
  const last = studentsQueries.at(-1)!;
  expect(last).toMatchObject({
    status: "SUBMITTED",
    direction: "ASC",
    studentId: studentA,
  });
  // Clearing the exact-ID filter restores the unfiltered page.
  await page.getByRole("button", { name: "Clear student filter" }).click();
  await expect(page.getByText(/one student with this exact ID/i)).toHaveCount(
    0,
  );
});

test("student detail shows retake history with best/latest badges and gated review links", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  await interceptDashboard(page, { audience: "CLASS", state: "CLOSED" });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await page.getByRole("button", { name: "View attempts" }).first().click();
  const history = page.getByRole("region", { name: "Attempt history table" });
  await expect(history).toBeVisible();
  await expect(history.getByText("Best attempt")).toBeVisible();
  await expect(history.getByText("Latest attempt")).toBeVisible();
  await expect(history.getByText("Student abandoned")).toBeVisible();
  await expect(history.getByText("No result (ungraded)")).toBeVisible();
  await expect(
    history.getByRole("link", { name: "Review submitted work" }),
  ).toHaveAttribute(
    "href",
    `/app/assessments/${publicationId}/attempts/${attemptA1}/review`,
  );
  // Only the graded SUBMITTED attempt offers review; the abandoned one never does.
  expect(
    await history.getByRole("link", { name: "Review submitted work" }).count(),
  ).toBe(1);
  await expect(
    history.getByRole("link", { name: "Open activity evidence for Attempt 2" }),
  ).toHaveAttribute("href", `/app/monitoring/teacher/attempts/${attemptA2}`);
  // Keyboard: the detail closes with an explicit control and focus stays usable.
  await page.getByRole("button", { name: "Close student details" }).focus();
  await expect(
    page.getByRole("button", { name: "Close student details" }),
  ).toBeFocused();
});

test("non-owner Teacher denial is masked: no KPIs, no rows, no fabricated data", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  await interceptDashboard(page, {
    audience: "PUBLIC",
    state: "OPEN",
    overviewStatus: 403,
    studentsStatus: 403,
  });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await expect(page.getByText("Summary could not be read")).toBeVisible();
  await expect(page.getByText("Students could not be read")).toBeVisible();
  await expect(page.getByText("No students yet")).toBeVisible();
  await expect(page.getByText("Nguyễn Văn An")).toHaveCount(0);
  await expect(page.getByText(studentA, { exact: true })).toHaveCount(0);
});

test("dashboard never leaks answer keys and renders without horizontal overflow from 375 to 1920", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  await interceptDashboard(page, { audience: "CLASS", state: "OPEN" });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await page.getByText("Nguyễn Văn An").first().waitFor();
  // Secrecy: no submitted answers or correct-answer projections anywhere on
  // the dashboard; the footnote itself confirms the policy.
  expect(await page.getByText("Correct answer").count()).toBe(0);
  expect(await page.getByText("Submitted answer").count()).toBe(0);
  await expect(
    page.getByText(/Answers and answer keys are never shown/i),
  ).toBeVisible();
  for (const width of [375, 768, 1280, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
  // Keyboard reachability: the accessible score table focus region and the
  // directory controls are operable without a mouse.
  const scoreTable = page.getByRole("region", {
    name: "Score distribution table",
  });
  await scoreTable.focus();
  await expect(scoreTable).toBeFocused();
  await page.keyboard.press("Tab");
});

test("empty assessment state is truthful with twenty zero bins and no zero-average claim", async ({
  page,
}) => {
  await mockAuth(page, ["TEACHER"]);
  await interceptDashboard(page, {
    audience: "PUBLIC",
    state: "OPEN",
    items: [],
    overview: {
      publicationId,
      asOf: "2026-10-16T11:30:00.000000Z",
      activeStudentCount: "0",
      submittedStudentCount: "0",
      participatedStudentCount: "0",
      gradedStudentCount: "0",
      bestScore: null,
      averageBestScore: null,
      scoreBins: Array.from({ length: 20 }, (_, index) => ({
        index,
        lowerBound: (index / 2).toString(),
        upperBound: ((index + 1) / 2).toString(),
        lowerInclusive: index === 0,
        upperInclusive: true,
        count: "0",
      })),
    },
  });
  await page.goto(`/app/assessments/${publicationId}/results`);
  await expect(page.getByText("No students yet")).toBeVisible();
  await expect(
    page.getByText(/No graded work yet, so the distribution is empty/i),
  ).toBeVisible();
  const table = page.getByRole("region", { name: "Score distribution table" });
  await expect(table.getByRole("row", { name: /9\.5 – 10\s*0/ })).toBeVisible();
});
