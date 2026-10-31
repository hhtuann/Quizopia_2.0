import { expect, test, type Page } from "@playwright/test";
import type {
  Configuration,
  OwnerView,
  PublicationSummary,
} from "../../features/assessments/api/publication-contract.generated";

// These journeys assert English UI strings while the app defaults to
// Vietnamese; pin the locale cookie before any navigation (SSR reads it).
test.beforeEach(async ({ page }) => {
  await page
    .context()
    .addCookies([
      { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
    ]);
});

// Production browser UI + authenticated executor, intercepted Gateway contracts.
// This suite does not claim live backend ownership/admission verification.
const teacherId = "a7a4c564-3c27-4e6d-91aa-a004334aa8f9";
const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const classroomId = "f7b14962-3a2e-4d2d-926a-3b36ea90c199";
const secondClassroomId = "b7b14962-3a2e-4d2d-926a-3b36ea90c199";
const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const quizVersionId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const sourceUrl = `/app/assessments/new?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3`;
const configuration: Configuration = {
  title: "Network fundamentals",
  description: "Prepare your learners for the next step.",
  audience: "CLASS",
  classroomId,
  quizId,
  quizVersionId,
  versionNumber: 3,
  startAt: "2026-10-26T08:00:00Z",
  endAt: "2026-10-26T09:00:00Z",
  durationMinutes: 30,
  monitoringChoice: "UNCONFIGURED",
  maxAttempts: 1,
};
const draft: OwnerView = {
  publicationId,
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-25T08:00:00Z",
  openedAt: null,
  privateLocator: null,
};
const summary: PublicationSummary = {
  publicationId,
  title: configuration.title,
  audience: "CLASS",
  state: "DRAFT",
  startAt: configuration.startAt,
  endAt: configuration.endAt,
  createdAt: draft.createdAt,
  revision: 0,
  quizId,
  quizVersionId,
  versionNumber: 3,
};
const classrooms = [
  {
    id: classroomId,
    name: "Computer Science 12A",
    subject: "Computer Science",
    grade: "Grade 12",
    description: "Morning group · Network fundamentals",
    createdAt: draft.createdAt,
  },
  {
    id: secondClassroomId,
    name: "Computer Science 12B",
    subject: "Computer Science",
    grade: "Grade 12",
    description: null,
    createdAt: draft.createdAt,
  },
];
async function auth(page: Page, roles = ["TEACHER"]) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      json: {
        accessToken: "directory-browser-token",
        tokenType: "Bearer",
        expiresIn: 300,
      },
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: teacherId,
        username: "teacher",
        email: "teacher@gmail.com",
        roles,
      },
    }),
  );
}
async function source(page: Page) {
  await page.route(`**/api/quizzes/${quizId}/versions/3`, (route) =>
    route.fulfill({
      json: {
        id: quizVersionId,
        quizId,
        versionNumber: 3,
        titleSnapshot: configuration.title,
        descriptionSnapshot: configuration.description,
        contentSchemaVersion: 1,
        createdAt: draft.createdAt,
        sourceSnapshot: "Câu 1 [NUMERIC_FILL]: Number?\nĐáp án: 0042",
        structuredContent: { questions: [] },
      },
    }),
  );
}
async function mockClasses(page: Page) {
  await page.route("**/classrooms?**", (route) => {
    expect(route.request().headers().authorization).toBe(
      "Bearer directory-browser-token",
    );
    const params = new URL(route.request().url()).searchParams;
    expect(params.get("limit")).toBe("20");
    return route.fulfill({
      json: {
        items: params.has("cursor") ? [classrooms[1]] : [classrooms[0]],
        nextCursor: params.has("cursor") ? null : "owned-classes-next",
      },
    });
  });
}
async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth,
    ),
  ).toBeLessThanOrEqual(1);
}

for (const viewport of [
  { width: 1920, height: 1080 },
  { width: 1440, height: 900 },
  { width: 768, height: 1024 },
  { width: 390, height: 844 },
]) {
  test(`directory and Classroom selector evidence ${viewport.width}`, async ({
    page,
  }, info) => {
    await page.setViewportSize(viewport);
    await auth(page);
    await source(page);
    await mockClasses(page);
    await page.route("**/api/assessments/publications?**", (route) =>
      route.fulfill({
        json: {
          items: [
            summary,
            {
              ...summary,
              publicationId: quizId,
              title: "Weekly knowledge check",
              audience: "PUBLIC",
              state: "OPEN",
            },
            {
              ...summary,
              publicationId: quizVersionId,
              title: "Completed midterm assessment",
              state: "CLOSED",
            },
          ],
          nextCursor: null,
        },
      }),
    );
    await page.goto("/app/assessments");
    await expect(
      page.getByRole("heading", { name: "My Assessments" }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Results for Weekly knowledge check" }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: `Results for ${summary.title}` }),
    ).toHaveCount(0);
    await noOverflow(page);
    await page.screenshot({
      path: info.outputPath(`my-assessments-${viewport.width}.png`),
      fullPage: true,
    });
    await page.goto(sourceUrl);
    const choice = page.getByRole("radio", {
      name: classrooms[0]!.name,
      exact: true,
    });
    await expect(choice).toBeVisible();
    await expect(choice).not.toBeChecked();
    await choice.focus();
    await page.keyboard.press("Space");
    await expect(choice).toBeChecked();
    await page.getByRole("button", { name: "Load more Classrooms" }).click();
    const second = page.getByRole("radio", {
      name: classrooms[1]!.name,
      exact: true,
    });
    await expect(second).toBeVisible();
    await choice.focus();
    await page.keyboard.press("ArrowDown");
    await expect(second).toBeChecked();
    await noOverflow(page);
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
    await page.screenshot({
      path: info.outputPath(`classroom-selector-${viewport.width}.png`),
      fullPage: true,
    });
    page.once("dialog", (dialog) => dialog.dismiss());
    await page
      .getByRole("link", { name: "My Assessments", exact: true })
      .click();
    await expect(page).toHaveURL(new RegExp("/app/assessments/new"));
    await expect(second).toBeChecked();
  });
}

test("owner directory recovers, paginates and sends server title/state filters", async ({
  page,
}) => {
  await auth(page);
  let unavailable = true;
  await page.route("**/api/assessments/publications?**", (route) => {
    const query = new URL(route.request().url()).searchParams;
    expect(route.request().headers().authorization).toBe(
      "Bearer directory-browser-token",
    );
    expect(query.has("ownerId")).toBe(false);
    if (unavailable)
      return route.fulfill({
        status: 503,
        json: { code: "AUTHORITY_UNAVAILABLE" },
      });
    if (query.get("state") === "CLOSED") {
      expect(query.get("search")).toBe("network");
      expect(query.has("cursor")).toBe(false);
      return route.fulfill({ json: { items: [], nextCursor: null } });
    }
    return route.fulfill({
      json: {
        items: query.has("cursor")
          ? [
              {
                ...summary,
                publicationId: quizId,
                title: "More owned assessments",
                state: "OPEN",
              },
            ]
          : [summary],
        nextCursor: query.has("cursor") ? null : "owned-page-next",
      },
    });
  });
  await page.goto("/app/assessments");
  await expect(
    page.getByRole("button", { name: "Retry assessments" }),
  ).toBeVisible();
  unavailable = false;
  await page.getByRole("button", { name: "Retry assessments" }).click();
  await expect(
    page.getByRole("link", { name: `Manage ${summary.title}` }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Load more assessments" }).click();
  await expect(
    page.getByRole("link", { name: "Manage More owned assessments" }),
  ).toBeVisible();
  await page.getByLabel("Search assessments").fill(" network ");
  await page.getByLabel("Publication state").selectOption("CLOSED");
  await expect(page.getByText("No matching assessments")).toBeVisible();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(
    page.getByRole("link", { name: `Manage ${summary.title}` }),
  ).toBeVisible();
});

test("directory rows are keyboard navigable: Tab reaches the title link then Results and Enter opens detail", async ({
  page,
}) => {
  await auth(page);
  const openItem: PublicationSummary = {
    ...summary,
    publicationId: quizId,
    title: "Weekly knowledge check",
    audience: "PUBLIC",
    state: "OPEN",
  };
  await page.route("**/api/assessments/publications?**", (route) =>
    route.fulfill({
      json: { items: [summary, openItem], nextCursor: null },
    }),
  );
  await page.goto("/app/assessments");
  const draftTitle = page.getByRole("link", {
    name: `Manage ${summary.title}`,
  });
  const openTitle = page.getByRole("link", {
    name: "Manage Weekly knowledge check",
  });
  const resultsLink = page.getByRole("link", {
    name: "Results for Weekly knowledge check",
  });
  await draftTitle.focus();
  await page.keyboard.press("Tab");
  // The row title link is the primary navigation and is tabbable.
  await expect(openTitle).toBeFocused();
  await page.keyboard.press("Tab");
  // The nested Results link remains a separately tabbable secondary action.
  await expect(resultsLink).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(openTitle).toBeFocused();
  // Enter on the focused title link navigates to the detail page.
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(`/app/assessments/${quizId}`);
});

test("OPEN detail embeds the Results section and toggles the configuration summary by keyboard", async ({
  page,
}) => {
  await auth(page);
  const attemptId = "a7b14962-3a2e-4d2d-926a-3b36ea90c1a7";
  const openView: OwnerView = {
    ...draft,
    state: "OPEN",
    revision: 1,
    openedAt: "2026-10-26T07:30:00Z",
    configuration: {
      ...configuration,
      monitoringChoice: "DISABLED",
    },
  };
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) => route.fulfill({ json: openView }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/student-overview`,
    (route) =>
      route.fulfill({
        json: {
          publicationId,
          asOf: "2026-10-26T09:30:00Z",
          activeStudentCount: "0",
          submittedStudentCount: "1",
          participatedStudentCount: "1",
          gradedStudentCount: "1",
          bestScore: {
            earnedWeight: "8.5",
            possibleWeight: "10",
            scoreDisplay: "8.50",
          },
          averageBestScore: {
            sumEarnedWeight: "8.5",
            sumPossibleWeight: "10",
            gradedAttemptCount: "1",
            scoreDisplay: "8.50",
          },
          scoreBins: Array.from({ length: 20 }, (_, index) => ({
            index,
            lowerBound: (index / 2).toString(),
            upperBound: ((index + 1) / 2).toString(),
            lowerInclusive: index === 0,
            upperInclusive: true,
            count: index === 16 ? "1" : "0",
          })),
        },
      }),
  );
  const graded = {
    resultId: "b7b14962-3a2e-4d2d-926a-3b36ea90c1b8",
    attemptId,
    publicationId,
    ordinal: "1",
    earnedWeight: "8.5",
    possibleWeight: "10",
    percentageDisplay: "85.00",
    gradingPolicyVersion: 1,
    gradingPolicyId: "QUIZOPIA_GRADING_V1",
    terminalReason: "MANUAL_SUBMIT",
    finalizedAt: "2026-10-26T09:00:00Z",
  };
  await page.route(
    `**/api/assessments/publications/${publicationId}/students**`,
    (route) =>
      route.fulfill({
        json: {
          publicationId,
          asOf: "2026-10-26T09:30:00Z",
          items: [
            {
              studentId: teacherId,
              studentName: null,
              attemptCount: "1",
              gradedAttemptCount: "1",
              hasActive: false,
              hasSubmitted: true,
              latestAttempt: {
                attemptId,
                ordinal: "1",
                state: "SUBMITTED",
                startedAt: "2026-10-26T08:00:00Z",
                deadlineAt: null,
                finalizedAt: "2026-10-26T09:00:00Z",
                terminalReason: "MANUAL_SUBMIT",
                result: graded,
                teacherReviewAvailable: true,
              },
              latestGradedResult: graded,
              bestResult: graded,
              averageResult: {
                sumEarnedWeight: "8.5",
                sumPossibleWeight: "10",
                gradedAttemptCount: "1",
                scoreDisplay: "8.50",
              },
            },
          ],
          nextCursor: null,
        },
      }),
  );
  await page.goto(`/app/assessments/${publicationId}`);
  // The embedded unified dashboard renders fetched owner-authorized rows.
  await expect(
    page.getByRole("heading", { name: "Student results", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(teacherId, { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByText("85.00%").first()).toBeVisible();
  // Lifecycle controls remain available on the unified detail page.
  await expect(
    page.getByRole("button", { name: "Review and CLOSE" }),
  ).toBeVisible();
  // Desktop (lg+) defaults the configuration disclosure to expanded. The
  // collapsed content stays in the DOM, so assert visibility, not count.
  await expect(page.getByText("Source version")).toBeVisible();
  const toggle = page.getByText("Configuration summary");
  await toggle.focus();
  await expect(toggle).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByText("Source version")).toBeHidden();
  await page.keyboard.press("Enter");
  await expect(page.getByText("Source version")).toBeVisible();
});

test("mobile detail keeps the configuration summary collapsed by default without horizontal overflow", async ({
  page,
}) => {
  await auth(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const openView: OwnerView = {
    ...draft,
    state: "OPEN",
    revision: 1,
    openedAt: "2026-10-26T07:30:00Z",
    configuration: {
      ...configuration,
      monitoringChoice: "DISABLED",
    },
  };
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) => route.fulfill({ json: openView }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/student-overview`,
    (route) =>
      route.fulfill({
        json: {
          publicationId,
          asOf: "2026-10-26T09:30:00Z",
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
      }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}/students**`,
    (route) =>
      route.fulfill({
        json: {
          publicationId,
          asOf: "2026-10-26T09:30:00Z",
          items: [],
          nextCursor: null,
        },
      }),
  );
  await page.goto(`/app/assessments/${publicationId}`);
  // Compact chips are visible while the full summary starts collapsed.
  await expect(page.getByText("Configuration summary")).toBeVisible();
  await expect(page.getByText("Source version")).toBeHidden();
  await expect(
    page.getByRole("heading", { name: "Student results", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("No students yet")).toBeVisible();
  const toggle = page.getByText("Configuration summary");
  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByText("Source version")).toBeVisible();
  expect(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth,
    ),
  ).toBeLessThanOrEqual(1);
});

test("draft edit resolves an off-page Classroom, preserves selection on cancel, and PUBLIC clears it", async ({
  page,
}) => {
  await auth(page);
  await mockClasses(page);
  const savedClass = {
    ...classrooms[1]!,
    id: quizVersionId,
    name: "Saved advanced group",
  };
  await page.route(`**/classrooms/${quizVersionId}`, (route) =>
    route.fulfill({ json: { ...savedClass, ownerTeacherUserId: teacherId } }),
  );
  let current: OwnerView = {
    ...draft,
    configuration: { ...configuration, classroomId: quizVersionId },
  };
  let sent: Configuration | undefined;
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    async (route) => {
      if (route.request().method() === "PUT") {
        sent = route.request().postDataJSON().configuration;
        current = { ...current, configuration: sent!, revision: 1 };
      }
      await route.fulfill({ json: current });
    },
  );
  await page.goto(`/app/assessments/${publicationId}`);
  await page.getByRole("button", { name: "Edit DRAFT", exact: true }).click();
  const selected = page.getByRole("radio", { name: savedClass.name });
  await expect(selected).toBeChecked();
  await page
    .getByLabel("Publication title")
    .fill("Unsaved classroom assessment");
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "Cancel editing" }).click();
  await expect(page.getByLabel("Publication title")).toHaveValue(
    "Unsaved classroom assessment",
  );
  await expect(selected).toBeChecked();
  await page.getByRole("radio", { name: "Public", exact: true }).check();
  await expect(selected).toHaveCount(0);
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Save DRAFT", exact: true })
    .click();
  await expect.poll(() => sent?.audience).toBe("PUBLIC");
  expect(sent?.classroomId).toBeNull();
  expect(sent?.monitoringChoice).toBe("DISABLED");
});

test("inaccessible saved Classroom cannot be submitted and a failed directory can retry", async ({
  page,
}) => {
  await auth(page);
  let unavailable = true;
  await page.route("**/classrooms?**", (route) => {
    return unavailable
      ? route.fulfill({ status: 503, json: { code: "AUTHORITY_UNAVAILABLE" } })
      : route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.route(`**/classrooms/${classroomId}`, (route) =>
    route.fulfill({ status: 404, json: { code: "CLASSROOM_NOT_ACCESSIBLE" } }),
  );
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) => route.fulfill({ json: draft }),
  );
  await page.goto(`/app/assessments/${publicationId}`);
  await page.getByRole("button", { name: "Edit DRAFT", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Retry Classrooms" }),
  ).toBeVisible();
  unavailable = false;
  await page.getByRole("button", { name: "Retry Classrooms" }).click();
  await expect(page.getByText(/No classrooms yet/)).toBeVisible();
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  await expect(
    page.getByText("Select an accessible owned Classroom before continuing."),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("radio", { name: "Choose later — DRAFT only" }),
  ).toBeChecked();
});

test("STUDENT and ADMIN-only users cannot invoke owner directories", async ({
  page,
}) => {
  await auth(page, ["STUDENT", "ADMIN"]);
  let calls = 0;
  await page.route("**/api/assessments/publications?**", (route) => {
    calls++;
    return route.abort();
  });
  await page.goto("/app/assessments");
  await expect(page.getByText("Teacher access required")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "My Assessments", exact: true }),
  ).toHaveCount(0);
  expect(calls).toBe(0);
});

test("Teaching home discovers the directory and creates a CLASS DRAFT from an immutable version", async ({
  page,
}) => {
  await auth(page);
  await source(page);
  await mockClasses(page);
  await page.route("**/api/assessments/publications?**", (route) =>
    route.fulfill({ json: { items: [], nextCursor: null } }),
  );
  let sent: Configuration | undefined;
  await page.route("**/api/assessments/publications", async (route) => {
    expect(route.request().method()).toBe("POST");
    sent = route.request().postDataJSON();
    await route.fulfill({
      status: 201,
      json: { ...draft, configuration: sent },
    });
  });
  await page.route(
    `**/api/assessments/publications/${publicationId}`,
    (route) =>
      route.fulfill({
        json: { ...draft, configuration: sent ?? configuration },
      }),
  );
  await page.goto("/app");
  await page
    .getByRole("main")
    .getByRole("link", { name: "My Assessments" })
    .click();
  await expect(page.getByText("No assessments yet")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Create assessment" }),
  ).toHaveAttribute("href", "/app/quizzes");
  await page.goto(sourceUrl);
  const radio = page.getByRole("radio", {
    name: classrooms[0]!.name,
    exact: true,
  });
  await expect(radio).not.toBeChecked();
  await radio.check();
  for (const [label, instant] of [
    ["Participation start", configuration.startAt],
    ["Participation end", configuration.endAt],
  ]) {
    const date = new Date(instant!);
    const pad = (value: number) => String(value).padStart(2, "0");
    await page
      .getByLabel(label!, { exact: true })
      .fill(
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
      );
    await page
      .getByLabel(`${label} time`, { exact: true })
      .fill(`${pad(date.getHours())}:${pad(date.getMinutes())}`);
  }
  await page
    .getByRole("radio", { name: "Disabled — no monitoring", exact: true })
    .check();
  await page.getByRole("button", { name: "Review and create DRAFT" }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Create DRAFT", exact: true })
    .click();
  await expect(page).toHaveURL(`/app/assessments/${publicationId}`);
  expect(sent).toMatchObject({
    audience: "CLASS",
    classroomId,
    quizId,
    quizVersionId,
    versionNumber: 3,
    monitoringChoice: "DISABLED",
  });
});

test("a late search cannot replace a newer state-filter response", async ({
  page,
}) => {
  await auth(page);
  let release!: () => void;
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  let slowStarted!: () => void;
  const started = new Promise<void>((resolve) => {
    slowStarted = resolve;
  });
  await page.route("**/api/assessments/publications?**", async (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (query.get("search") === "slow" && !query.get("state")) {
      slowStarted();
      await hold;
      await route
        .fulfill({ json: { items: [summary], nextCursor: null } })
        .catch(() => {});
    } else await route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto("/app/assessments");
  await expect(page.getByText("No assessments yet")).toBeVisible();
  await page.getByLabel("Search assessments").fill("slow");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await started;
  await page.getByLabel("Publication state").selectOption("OPEN");
  await expect(page.getByText("No matching assessments")).toBeVisible();
  release();
  await expect(
    page.getByRole("link", { name: `Manage ${summary.title}` }),
  ).toHaveCount(0);
});

test("language switcher preserves an in-progress DRAFT form and the locale persists across reload", async ({
  page,
}) => {
  await auth(page);
  await source(page);
  await mockClasses(page);
  await page.goto(sourceUrl);
  const title = page.getByLabel("Publication title");
  await title.fill("Bilingual unsaved draft");
  const classroom = page.getByRole("radio", {
    name: classrooms[0]!.name,
    exact: true,
  });
  await classroom.check();
  await expect(classroom).toBeChecked();
  await page.getByLabel("Attempt duration (minutes)").fill("45");

  // Switch the live SPA to Vietnamese through the user menu language options.
  await page.getByRole("button", { name: /Open user menu for/ }).click();
  await page.getByRole("menuitemradio", { name: "Tiếng Việt" }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByLabel("Tiêu đề Publication")).toHaveValue(
    "Bilingual unsaved draft",
  );
  await expect(classroom).toBeChecked();
  await expect(page.getByLabel("Thời lượng Attempt (phút)")).toHaveValue("45");
  await expect(
    page.getByRole("button", { name: "Xem lại và tạo DRAFT" }),
  ).toBeVisible();
  await expect(
    page.getByRole("group", { name: "Chọn một lớp học" }),
  ).toBeVisible();

  // The preference cookie keeps the server-rendered locale across reload.
  const cookies = await page.context().cookies();
  expect(
    cookies.find((cookie) => cookie.name === "quizopia-locale")?.value,
  ).toBe("vi");
  await page.reload();
  await expect(page.getByLabel("Tiêu đề Publication")).toBeVisible();
  await expect(await page.evaluate(() => document.documentElement.lang)).toBe(
    "vi",
  );
});
