import { expect, test, type Page } from "@playwright/test";
import type {
  Configuration,
  OwnerView,
} from "../../features/assessments/api/publication-contract.generated";

// Browser contract tests: production UI/executor, intercepted Gateway responses.
// These do not establish real Identity/Assessment/Classroom/Redis compatibility.
const publicationId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";
const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const quizVersionId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
const locator = "P".repeat(43);
const configuration: Configuration = {
  title: "Pinned version assessment",
  description: "Version three",
  quizId,
  quizVersionId,
  versionNumber: 3,
  audience: "PUBLIC",
  classroomId: null,
  startAt: "2026-10-16T08:00:00Z",
  endAt: "2026-10-16T09:00:00Z",
  durationMinutes: 40,
  monitoringChoice: "DISABLED",
  maxAttempts: 1,
};
const draft: OwnerView = {
  publicationId,
  configuration,
  state: "DRAFT",
  revision: 0,
  createdAt: "2026-10-16T07:00:00Z",
  openedAt: null,
  privateLocator: locator,
};

async function mockAuth(page: Page, roles: string[] = ["TEACHER"]) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "publication-browser-token",
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
        id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
        username: "teacher",
        email: "teacher@gmail.com",
        roles,
      }),
    }),
  );
}

async function mockPublication(
  page: Page,
  options: { initial?: OwnerView; blockClose?: boolean } = {},
) {
  let current = structuredClone(options.initial ?? draft);
  const commands: {
    method: string;
    path: string;
    body: unknown;
    key: string | undefined;
  }[] = [];
  await page.route("**/api/assessments/publications**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    expect(request.headers().authorization).toBe(
      "Bearer publication-browser-token",
    );
    expect(request.url()).not.toContain(locator);
    if (method === "GET") {
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify(current),
      });
      return;
    }
    const body = request.postDataJSON() as Record<string, unknown>;
    const key = request.headers()["idempotency-key"];
    commands.push({ method, path, body, key });
    if (path.endsWith("/close")) {
      expect(Object.keys(body)).toEqual(["expectedRevision"]);
      expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
      if (options.blockClose) {
        await route.fulfill({
          contentType: "application/json",
          status: 409,
          body: JSON.stringify({ code: "ACTIVE_ATTEMPTS" }),
        });
        return;
      }
      current = { ...current, state: "CLOSED", revision: current.revision + 1 };
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          publicationId,
          state: "CLOSED",
          revision: current.revision,
          closedAt: "2026-10-16T08:30:00Z",
        }),
      });
      return;
    }
    if (path.endsWith("/open")) {
      current = {
        ...current,
        state: "OPEN",
        revision: current.revision + 1,
        openedAt: "2026-10-16T07:30:00Z",
      };
    } else if (path.endsWith("/schedule")) {
      current = {
        ...current,
        revision: current.revision + 1,
        configuration: {
          ...current.configuration,
          endAt: body.endAt as string,
          durationMinutes: body.durationMinutes as number,
        },
      };
    } else if (method === "PUT") {
      current = {
        ...current,
        revision: current.revision + 1,
        configuration: body.configuration as Configuration,
      };
    } else {
      expect(path).toBe("/api/assessments/publications");
      expect(key).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
      current = { ...draft, configuration: body as unknown as Configuration };
    }
    await route.fulfill({
      contentType: "application/json",
      status: path === "/api/assessments/publications" ? 201 : 200,
      body: JSON.stringify(current),
    });
  });
  return commands;
}

async function mockQuiz(page: Page) {
  const summary = {
    id: quizVersionId,
    quizId,
    versionNumber: 3,
    titleSnapshot: configuration.title,
    descriptionSnapshot: "Version three",
    contentSchemaVersion: 1,
    createdAt: draft.createdAt,
  };
  await page.route(`**/api/quizzes/${quizId}/draft`, (route) =>
    route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Unpublished different draft",
        description: "Mutable",
        authoringSource: "Câu 1 [NUMERIC_FILL]: Current draft?\nĐáp án: 99",
        createdAt: draft.createdAt,
        updatedAt: draft.createdAt,
      }),
    }),
  );
  await page.route(`**/api/quizzes/${quizId}/versions**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
    const value = path.endsWith("/versions")
      ? {
          items: [
            {
              ...summary,
              id: publicationId,
              versionNumber: 4,
              titleSnapshot: "Latest unrelated version",
            },
            summary,
          ],
          nextCursor: null,
        }
      : {
          ...summary,
          sourceSnapshot: "Câu 1 [NUMERIC_FILL]: Published source?\nĐáp án: 42",
          structuredContent: {
            questions: [
              {
                number: 1,
                type: "NUMERIC_FILL",
                stemMarkdown: "Published source?",
                options: [],
                numericAnswer: "42",
                explanationMarkdown: null,
              },
            ],
          },
        };
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(value),
    });
  });
}

async function confirm(page: Page, label: string) {
  await page
    .getByRole("dialog")
    .getByRole("button", { name: label, exact: true })
    .click();
}

test("intercepted history selection completes reviewed create/edit/OPEN/extend/CLOSE with pinned source", async ({
  page,
}) => {
  await mockAuth(page);
  await mockQuiz(page);
  const commands = await mockPublication(page);
  await page.goto(`/app/quizzes/${quizId}`);
  await page
    .getByRole("textbox", { name: "Quiz title" })
    .fill("Unsaved current draft title");
  await page.getByRole("button", { name: "Published versions" }).click();
  await page
    .getByRole("list", { name: "Published version history" })
    .getByRole("button", { name: /Version 3/ })
    .click();
  page.once("dialog", async (dialog) => {
    expect(dialog.type()).toBe("confirm");
    expect(dialog.message()).toContain("unsaved changes");
    await dialog.dismiss();
  });
  await page
    .getByRole("button", { name: "Create assessment from version 3" })
    .click();
  await expect(page).toHaveURL(`/app/quizzes/${quizId}`);
  await expect(
    page.getByRole("dialog", { name: "Published versions" }),
  ).toBeVisible();
  page.once("dialog", async (dialog) => dialog.accept());
  await page
    .getByRole("button", { name: "Create assessment from version 3" })
    .click();
  await expect(page).toHaveURL(
    new RegExp(
      `/app/assessments/new\\?quizId=${quizId}&quizVersionId=${quizVersionId}&versionNumber=3$`,
    ),
  );
  await page.getByRole("radio", { name: "Public", exact: true }).check();
  await page
    .getByLabel("Participation start (UTC)")
    .fill("2026-10-16T08:00:00.123456");
  await page
    .getByLabel("Participation end (UTC)")
    .fill("2026-10-16T09:00:00.654321");
  await page.getByRole("button", { name: "Review and create DRAFT" }).click();
  await expect(page.getByRole("dialog").getByText(quizVersionId)).toBeVisible();
  await confirm(page, "Create DRAFT");
  await expect(page).toHaveURL(`/app/assessments/${publicationId}`);
  await page.getByRole("button", { name: "Edit DRAFT" }).click();
  await expect(page.getByLabel("Participation start (UTC)")).toHaveValue(
    "2026-10-16T08:00:00.123456",
  );
  await expect(page.getByLabel("Participation end (UTC)")).toHaveValue(
    "2026-10-16T09:00:00.654321",
  );
  await page.getByLabel("Publication title").fill("Reviewed edited assessment");
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  await confirm(page, "Save DRAFT");
  await expect(
    page.getByRole("heading", { name: "Reviewed edited assessment" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Review and OPEN", exact: true })
    .click();
  await confirm(page, "OPEN Publication");
  await page
    .getByRole("button", { name: "Extend participation schedule" })
    .click();
  await page
    .getByLabel("Extended participation end (UTC)")
    .fill("2026-10-16T10:00:00.654321");
  await page.getByLabel("Extended Attempt duration (minutes)").fill("45");
  await page.getByRole("button", { name: "Review schedule extension" }).click();
  await confirm(page, "Save schedule extension");
  await page.getByRole("button", { name: "Review and CLOSE" }).click();
  await confirm(page, "CLOSE Publication");
  await expect(
    page.getByText("Publication CLOSED", { exact: true }),
  ).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "test-results/w3a-public-desktop.png",
    fullPage: true,
  });
  expect(commands.map((command) => command.method)).toEqual([
    "POST",
    "PUT",
    "POST",
    "PATCH",
    "POST",
  ]);
  expect(commands[0]?.body).toMatchObject({
    quizId,
    quizVersionId,
    versionNumber: 3,
    audience: "PUBLIC",
    monitoringChoice: "DISABLED",
    classroomId: null,
    startAt: "2026-10-16T08:00:00.123456Z",
    endAt: "2026-10-16T09:00:00.654321Z",
  });
  expect(commands[1]?.body).toMatchObject({
    expectedRevision: 0,
    configuration: {
      quizVersionId,
      versionNumber: 3,
      startAt: "2026-10-16T08:00:00.123456Z",
      endAt: "2026-10-16T09:00:00.654321Z",
    },
  });
  expect(commands[2]?.body).toEqual({ expectedRevision: 1 });
  expect(commands[3]?.body).toEqual({
    expectedRevision: 2,
    endAt: "2026-10-16T10:00:00.654321Z",
    durationMinutes: 45,
  });
  expect(commands[4]?.body).toEqual({ expectedRevision: 3 });
  expect(
    commands.slice(1, 4).every((command) => command.key === undefined),
  ).toBe(true);
  expect(
    await page
      .locator("a")
      .evaluateAll((links) =>
        links.some((link) =>
          (link as HTMLAnchorElement).href.includes("P".repeat(43)),
        ),
      ),
  ).toBe(false);
});

test("intercepted CLASS requires explicit monitoring and blocked CLOSE remains OPEN at 375px", async ({
  page,
}) => {
  await mockAuth(page);
  const classDraft: OwnerView = {
    ...draft,
    privateLocator: null,
    configuration: {
      ...configuration,
      audience: "CLASS",
      classroomId: quizId,
      monitoringChoice: "UNCONFIGURED",
    },
  };
  const commands = await mockPublication(page, {
    initial: classDraft,
    blockClose: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`/app/assessments/${publicationId}`);
  await expect(
    page.getByRole("button", { name: "Review and OPEN" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Edit DRAFT" }).click();
  await page.getByRole("radio", { name: "Disabled — no monitoring" }).check();
  await page.getByRole("button", { name: "Review DRAFT changes" }).click();
  await confirm(page, "Save DRAFT");
  await page.getByRole("button", { name: "Review and OPEN" }).click();
  await confirm(page, "OPEN Publication");
  await page.getByRole("button", { name: "Review and CLOSE" }).click();
  await confirm(page, "CLOSE Publication");
  await expect(page.getByText(/Active Attempts prevent closure/)).toBeVisible();
  await expect(
    page.getByText("Publication CLOSED", { exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Review and CLOSE" }),
  ).toBeEnabled();
  expect(commands.at(-1)?.path.endsWith("/close")).toBe(true);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "test-results/w3a-class-mobile.png",
    fullPage: true,
  });
});

test("native confirmation traps keyboard focus, cancels with Escape and restores trigger focus", async ({
  page,
}) => {
  await mockAuth(page);
  const commands = await mockPublication(page);
  await page.goto(`/app/assessments/${publicationId}`);
  const trigger = page.getByRole("button", {
    name: "Review and OPEN",
    exact: true,
  });
  await trigger.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", {
    name: "Review and OPEN Publication",
  });
  await expect(
    dialog.getByRole("button", { name: "Cancel", exact: true }),
  ).toBeFocused();
  for (let index = 0; index < 5; index++) {
    await page.keyboard.press("Tab");
    const focus = await dialog.evaluate((element) => ({
      contained: element.contains(document.activeElement),
      tag: document.activeElement?.tagName,
      label: document.activeElement?.textContent?.slice(0, 80),
    }));
    expect(
      focus.contained,
      `Tab ${index + 1} focus: ${JSON.stringify(focus)}`,
    ).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  expect(commands).toHaveLength(0);
});

test("Student workspace cannot render Teacher publication controls or request owner metadata", async ({
  page,
}) => {
  await mockAuth(page, ["STUDENT"]);
  let publicationRequests = 0;
  await page.route("**/api/assessments/publications**", async (route) => {
    publicationRequests++;
    await route.abort();
  });
  await page.goto(`/app/assessments/${publicationId}`);
  await expect(
    page.getByText("Teacher access required", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Review and OPEN" }),
  ).toHaveCount(0);
  expect(publicationRequests).toBe(0);
});
