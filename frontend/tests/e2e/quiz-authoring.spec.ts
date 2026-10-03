import { expect, test, type Page } from "@playwright/test";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";
const versionId = "e7b14962-3a2e-4d2d-926a-3b36ea90c199";

const teacher = {
  email: "teacher@gmail.com",
  id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
  roles: ["STUDENT", "TEACHER"],
  username: "teacher",
};

async function mockTeacherBootstrap(page: Page) {
  await page.route("**/api/auth/refresh", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        accessToken: "quiz-authoring-e2e-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    });
  });
  await page.route("**/api/auth/me", async (route) => {
    expect(route.request().headers().authorization).toBe(
      "Bearer quiz-authoring-e2e-access",
    );
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(teacher),
    });
  });
}

async function switchToTeaching(page: Page) {
  const userMenu = page.getByRole("button", { name: /Open user menu/ });
  if ((await userMenu.count()) > 0) {
    await userMenu.click();
    await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
    return;
  }
  await page.getByRole("button", { name: "Switch to Teaching" }).click();
}

test("teacher creates, authors, saves, reloads and publishes a real-contract draft", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);

  let source = "";
  let title = "Untitled quiz";
  let description = "";
  let createCalls = 0;
  let updateCalls = 0;
  let publishCalls = 0;

  await page.route("**/api/quizzes", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({ items: [], nextCursor: null }),
      });
      return;
    }
    expect(route.request().method()).toBe("POST");
    createCalls += 1;
    const body = route.request().postDataJSON() as {
      authoringSource: string;
      description: string;
      title: string;
    };
    source = body.authoringSource;
    title = body.title;
    description = body.description;
    await route.fulfill({
      contentType: "application/json",
      status: 201,
      body: JSON.stringify({
        quizId,
        title,
        description,
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:00:00Z",
      }),
    });
  });

  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          quizId,
          title,
          description,
          authoringSource: source,
          createdAt: "2026-09-30T12:00:00Z",
          updatedAt: "2026-09-30T12:30:00Z",
        }),
      });
      return;
    }
    if (route.request().method() === "PUT") {
      updateCalls += 1;
      const body = route.request().postDataJSON() as {
        authoringSource: string;
        description: string;
        title: string;
      };
      source = body.authoringSource;
      title = body.title;
      description = body.description;
      await route.fulfill({
        contentType: "application/json",
        status: 200,
        body: JSON.stringify({
          quizId,
          title,
          description,
          authoringSource: source,
          createdAt: "2026-09-30T12:00:00Z",
          updatedAt: "2026-09-30T12:31:00Z",
        }),
      });
      return;
    }
    await route.abort();
  });

  await page.route(`**/api/quizzes/${quizId}/versions`, async (route) => {
    publishCalls += 1;
    expect(route.request().method()).toBe("POST");
    await route.fulfill({
      contentType: "application/json",
      status: 201,
      body: JSON.stringify({
        id: versionId,
        quizId,
        versionNumber: 1,
        createdAt: "2026-09-30T12:32:00Z",
      }),
    });
  });

  await page.goto("/app/quizzes");
  await expect(
    page.getByRole("heading", { name: "Open the Teaching workspace" }),
  ).toBeVisible();
  await switchToTeaching(page);
  await expect(
    page.getByRole("heading", { name: "Quiz authoring" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "No quizzes yet" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Create quiz" }).click();

  await expect(page).toHaveURL(new RegExp(`/app/quizzes/${quizId}$`));
  const editorBrand = page.getByRole("link", { name: "Back to Quiz Library" });
  await expect(editorBrand.getByTestId("quizopia-brand-mark")).toBeVisible();
  await expect(editorBrand).toContainText("Quizopia");
  await expect(editorBrand).toContainText("version 2.0");
  await expect(page.getByRole("textbox", { name: "Quiz title" })).toHaveValue(
    "Untitled quiz",
  );
  expect(createCalls).toBe(1);
  expect({ source, title, description }).toEqual({
    source: "",
    title: "Untitled quiz",
    description: "",
  });

  title = "Network quiz";
  await page.getByRole("textbox", { name: "Quiz title" }).fill(title);

  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await editor.fill("câu");
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toBeVisible();
  await expect(page.getByRole("option")).toHaveCount(4);
  await editor.press("ArrowDown");
  await editor.press("Enter");
  await expect(editor).toHaveValue("Câu 1 [MULTIPLE_CHOICE]: ");

  const exactSource =
    "Câu 1 [SINGLE_CHOICE]: Chọn giao thức\n*A. HTTP\nB. FTP\nC. TCP\nD. UDP\n\nLời giải:  keep  spacing";
  await editor.fill(exactSource);
  await page
    .getByRole("button", { name: /Jump to source for question 1/ })
    .click();
  await expect(editor).toBeFocused();
  await page.getByRole("button", { name: "B. Not marked correct" }).click();
  const previewEditedSource = exactSource
    .replace("*A. HTTP", "A. HTTP")
    .replace("B. FTP", "*B. FTP");
  await expect(editor).toHaveValue(previewEditedSource);
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toHaveCount(0);
  await editor.fill(`${previewEditedSource}\nc`);
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toBeVisible();
  await editor.press("Escape");
  await editor.fill(previewEditedSource);
  await expect(page.getByText("Unsaved changes")).toBeVisible();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  expect(updateCalls).toBe(1);
  expect(source).toBe(previewEditedSource);

  await page.reload();
  await switchToTeaching(page);
  await expect(page.getByRole("textbox", { name: "Quiz title" })).toHaveValue(
    title,
  );
  await expect(
    page.getByRole("textbox", { name: "Quiz Markdown source" }),
  ).toHaveValue(previewEditedSource);

  await page.getByRole("button", { name: "Publish" }).click();
  description = "Created from Playwright";
  await page.getByLabel("Description").fill(description);
  await page.getByRole("button", { name: "Publish QuizVersion" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Publish complete" }),
  ).toContainText("Published immutable version 1");
  expect(publishCalls).toBe(1);
});

test("teacher lists a backend quiz and opens its existing draft", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const existingSource = "Câu 1 [NUMERIC_FILL]: 1 + 1?\nĐáp án: 2.00";

  await page.route("**/api/quizzes", async (route) => {
    expect(route.request().method()).toBe("GET");
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        items: [
          {
            quizId,
            title: "Existing quiz",
            description: "Loaded from the teacher library",
            createdAt: "2026-09-30T12:00:00Z",
            updatedAt: "2026-10-01T03:00:00Z",
            latestVersionNumber: 2,
          },
        ],
        nextCursor: null,
      }),
    });
  });
  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    expect(route.request().method()).toBe("GET");
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Existing quiz",
        description: "Loaded from the teacher library",
        authoringSource: existingSource,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-10-01T03:00:00Z",
      }),
    });
  });

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/app/quizzes");
  await switchToTeaching(page);
  const quizLink = page.getByRole("link", { name: "Existing quiz" });
  await expect(quizLink).toBeVisible();
  await expect(page.getByText("Latest version 2")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);

  await quizLink.click();
  await expect(page).toHaveURL(new RegExp(`/app/quizzes/${quizId}$`));
  await expect(page.getByRole("textbox", { name: "Quiz title" })).toHaveValue(
    "Existing quiz",
  );
  await expect(
    page.getByRole("textbox", { name: "Quiz Markdown source" }),
  ).toHaveValue(existingSource);
});

test("teacher loads the next opaque-cursor library page", async ({ page }) => {
  await mockTeacherBootstrap(page);
  const secondQuizId = "9bd4c564-3c27-4e6d-91aa-a004334aa8f7";
  const observedUrls: string[] = [];
  let cursorCalls = 0;

  await page.route("**/api/quizzes*", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    const requestUrl = route.request().url();
    observedUrls.push(requestUrl);
    const cursor = new URL(requestUrl).searchParams.get("cursor");
    if (cursor !== null) {
      cursorCalls += 1;
    }
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify(
        cursor === null
          ? {
              items: [
                {
                  quizId,
                  title: "First page quiz",
                  description: null,
                  createdAt: "2026-09-30T12:00:00Z",
                  updatedAt: "2026-10-01T03:00:00Z",
                  latestVersionNumber: null,
                },
              ],
              nextCursor: "opaque-next-token",
            }
          : {
              items: [
                {
                  quizId: secondQuizId,
                  title: "Second page quiz",
                  description: null,
                  createdAt: "2026-09-30T11:00:00Z",
                  updatedAt: "2026-10-01T02:00:00Z",
                  latestVersionNumber: 1,
                },
              ],
              nextCursor: null,
            },
      ),
    });
  });

  await page.goto("/app/quizzes");
  await switchToTeaching(page);
  await expect(
    page.getByRole("link", { name: "First page quiz" }),
  ).toBeVisible();
  const loadMore = page.getByRole("button", { name: "Load more" });
  await loadMore.focus();
  await expect(loadMore).toBeFocused();
  await loadMore.press("Enter");
  await expect(
    page.getByRole("link", { name: "Second page quiz" }),
  ).toBeVisible();
  expect(cursorCalls).toBe(1);
  expect(
    observedUrls.some(
      (url) => new URL(url).searchParams.get("cursor") === "opaque-next-token",
    ),
  ).toBe(true);
});

test("quiz authoring remains usable without horizontal overflow at 375px", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const source = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 2.50";
  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    expect(route.request().method()).toBe("GET");
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Responsive quiz",
        description: "Mobile editor",
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:30:00Z",
      }),
    });
  });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  await expect(page.getByRole("textbox", { name: "Quiz title" })).toHaveValue(
    "Responsive quiz",
  );

  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await expect(editor).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Live preview" }),
  ).toBeHidden();
  await page.getByRole("button", { name: "Preview" }).click();
  await expect(
    page.getByRole("heading", { name: "Live preview" }),
  ).toBeVisible();
  await expect(editor).toBeHidden();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
