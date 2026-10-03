import { expect, test, type Locator, type Page } from "@playwright/test";

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

async function visibleEditorTextEndPoint(page: Page, needle: string) {
  return page
    .getByTestId("quiz-markdown-highlight-layer")
    .evaluate((layer, target) => {
      const walker = document.createTreeWalker(layer, NodeFilter.SHOW_TEXT);
      let node = walker.nextNode();

      while (node !== null) {
        const text = node.textContent ?? "";
        const targetStart = text.indexOf(target);
        if (targetStart >= 0) {
          const range = document.createRange();
          range.setStart(node, targetStart + target.length);
          range.collapse(true);
          const rect = range.getBoundingClientRect();
          return { x: rect.x, y: rect.y + rect.height / 2 };
        }
        node = walker.nextNode();
      }

      throw new Error(`Unable to find visible editor text: ${target}`);
    }, needle);
}

async function clickVisibleEditorTextEnd(
  page: Page,
  editor: Locator,
  needle: string,
) {
  const point = await visibleEditorTextEndPoint(page, needle);

  const editorBox = await editor.boundingBox();
  expect(editorBox).not.toBeNull();
  await page.mouse.click(point.x, point.y);
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
  const postEditCaret = previewEditedSource.indexOf("*B. FTP") + "*B. ".length;
  await expect
    .poll(() =>
      editor.evaluate((element) => ({
        end: (element as HTMLTextAreaElement).selectionEnd,
        start: (element as HTMLTextAreaElement).selectionStart,
      })),
    )
    .toEqual({ end: postEditCaret, start: postEditCaret });

  await editor.click({ position: { x: 24, y: 72 } });
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toBeVisible();
  await expect(page.getByRole("option")).toHaveText(["B.", "*B."]);
  await page.getByRole("option", { name: "B.", exact: true }).click();
  const manuallyUnstarred = previewEditedSource.replace("*B. FTP", "B. FTP");
  await expect(editor).toHaveValue(manuallyUnstarred);
  await expect(editor).toBeFocused();

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

test("question marker replacement keeps visible text and textarea caret aligned", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const source =
    "Câu 1 [TRUE_FALSE_MATRIX]: đúng\n*A. alpha\nB. beta\nC. gamma\nD. delta";
  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Caret alignment quiz",
        description: "Caret alignment coverage",
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:30:00Z",
      }),
    });
  });

  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });

  const markerCaret = source.indexOf("TRUE_FALSE_MATRIX") + 4;
  await editor.evaluate((element, offset) => {
    const textarea = element as HTMLTextAreaElement;
    textarea.focus();
    textarea.setSelectionRange(offset, offset);
    textarea.dispatchEvent(new Event("select", { bubbles: true }));
  }, markerCaret);
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toBeVisible();
  await page.getByRole("option", { name: "Câu 1 [SINGLE_CHOICE]:" }).click();

  const replacedSource = source.replace(
    "Câu 1 [TRUE_FALSE_MATRIX]:",
    "Câu 1 [SINGLE_CHOICE]:",
  );
  await expect(editor).toHaveValue(replacedSource);
  await clickVisibleEditorTextEnd(page, editor, "đúng");
  await editor.press("a");
  await expect(editor).toHaveValue(replacedSource.replace("đúng", "đúnga"));
});

test("range selection touching a marker suppresses autocomplete until it collapses", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const source =
    "Câu 1 [SINGLE_CHOICE]: choose\n*A. alpha\nB. beta\nC. gamma\nD. delta";
  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Selection autocomplete quiz",
        description: "Selection autocomplete coverage",
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:30:00Z",
      }),
    });
  });

  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  const markerPoint = await visibleEditorTextEndPoint(page, "SING");
  const stemPoint = await visibleEditorTextEndPoint(page, "choose");

  await page.mouse.move(markerPoint.x, markerPoint.y);
  await page.mouse.down();
  await page.mouse.move(stemPoint.x, stemPoint.y, { steps: 8 });
  await page.mouse.up();
  await expect
    .poll(() =>
      editor.evaluate((element) => {
        const textarea = element as HTMLTextAreaElement;
        return textarea.selectionEnd - textarea.selectionStart;
      }),
    )
    .toBeGreaterThan(0);
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toHaveCount(0);

  await page.mouse.click(markerPoint.x, markerPoint.y);
  await expect
    .poll(() =>
      editor.evaluate((element) => {
        const textarea = element as HTMLTextAreaElement;
        return {
          end: textarea.selectionEnd,
          start: textarea.selectionStart,
        };
      }),
    )
    .toEqual({
      end: source.indexOf("SINGLE_CHOICE") + 4,
      start: source.indexOf("SINGLE_CHOICE") + 4,
    });
  await expect(
    page.getByRole("listbox", { name: "Quiz Markdown suggestions" }),
  ).toBeVisible();
  await expect(page.getByRole("option")).toHaveCount(4);
});

test("preview correctness navigation keeps editor overlays aligned near the source end", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const source = Array.from({ length: 4 }, (_, index) => {
    const number = index + 1;
    return [
      `Câu ${number} [SINGLE_CHOICE]: Question ${number}`,
      `*A. alpha ${number}`,
      `B. beta ${number}`,
      `C. gamma ${number}`,
      `D. delta ${number}`,
    ].join("\n");
  }).join("\n");

  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Preview scroll alignment quiz",
        description: "Preview scroll alignment coverage",
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:30:00Z",
      }),
    });
  });

  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  const questionFourOptions = page.getByRole("list", {
    name: "Options for question 4",
  });
  const optionB = questionFourOptions.getByRole("button", {
    name: "B. Not marked correct",
  });

  await optionB.click();
  await expect(editor).toBeFocused();
  await expect(editor).toHaveValue(
    source
      .replace("*A. alpha 4", "A. alpha 4")
      .replace("B. beta 4", "*B. beta 4"),
  );

  const scrollState = await editor.evaluate((element) => {
    const textarea = element as HTMLTextAreaElement;
    const lineNumbers = document.querySelector(
      '[data-testid="quiz-markdown-line-numbers"] > div',
    ) as HTMLElement | null;
    const highlight = document.querySelector(
      '[data-testid="quiz-markdown-highlight-layer"]',
    ) as HTMLElement | null;
    const activeLine = document.querySelector(
      '[data-testid="quiz-markdown-active-line"]',
    ) as HTMLElement | null;
    return {
      activeLineTop: activeLine?.style.top ?? null,
      highlightTransform: highlight?.style.transform ?? null,
      lineNumberTransform: lineNumbers?.style.transform ?? null,
      scrollTop: textarea.scrollTop,
    };
  });

  const visualScrollTop =
    scrollState.scrollTop === 0 ? 0 : -scrollState.scrollTop;
  expect(scrollState.lineNumberTransform).toBe(
    `translateY(${visualScrollTop}px)`,
  );
  expect(scrollState.highlightTransform).toBe(
    `translate(0px, ${visualScrollTop}px)`,
  );
  const finalSource = await editor.inputValue();
  const caret = finalSource.indexOf("*B. beta 4") + "*B. ".length;
  await expect
    .poll(() =>
      editor.evaluate((element) => ({
        end: (element as HTMLTextAreaElement).selectionEnd,
        start: (element as HTMLTextAreaElement).selectionStart,
      })),
    )
    .toEqual({ end: caret, start: caret });
  expect(scrollState.activeLineTop).toBe(
    `${12 + (18 - 1) * 24 - scrollState.scrollTop}px`,
  );
});

test("autocomplete follows a scrolled caret and flips inside the editor", async ({
  page,
}) => {
  await mockTeacherBootstrap(page);
  const longLine = "x".repeat(180);
  const source = `Câu 1 [NUMERIC_FILL]: ${longLine}\n${Array.from(
    { length: 24 },
    (_, index) => `detail line ${index + 1}`,
  ).join("\n")}\nĐáp án: 2.50`;
  await page.route(`**/api/quizzes/${quizId}/draft`, async (route) => {
    await route.fulfill({
      contentType: "application/json",
      status: 200,
      body: JSON.stringify({
        quizId,
        title: "Popup geometry quiz",
        description: "Geometry coverage",
        authoringSource: source,
        createdAt: "2026-09-30T12:00:00Z",
        updatedAt: "2026-09-30T12:30:00Z",
      }),
    });
  });

  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await editor.fill(`${source}\nCâu 2 [`);
  const popup = page.getByRole("listbox", {
    name: "Quiz Markdown suggestions",
  });
  await expect(popup).toBeVisible();
  await editor.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
  });
  await expect
    .poll(() => editor.evaluate((element) => element.scrollTop))
    .toBeGreaterThan(0);
  await expect(popup).toHaveAttribute("data-placement", "above");

  const frame = page.getByTestId("quiz-markdown-editor-frame");
  const [frameBox, popupBox] = await Promise.all([
    frame.boundingBox(),
    popup.boundingBox(),
  ]);
  expect(frameBox).not.toBeNull();
  expect(popupBox).not.toBeNull();
  expect(popupBox!.y).toBeGreaterThan(frameBox!.y + 40);
  expect(popupBox!.y).toBeGreaterThanOrEqual(frameBox!.y);
  expect(popupBox!.y + popupBox!.height).toBeLessThanOrEqual(
    frameBox!.y + frameBox!.height + 1,
  );

  await editor.evaluate((element) => {
    element.scrollLeft = 120;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
  });
  await expect
    .poll(() => editor.evaluate((element) => element.scrollLeft))
    .toBe(120);
  const scrolledPopupBox = await popup.boundingBox();
  expect(scrolledPopupBox).not.toBeNull();
  expect(scrolledPopupBox!.x).toBeGreaterThanOrEqual(frameBox!.x);
  expect(scrolledPopupBox!.x + scrolledPopupBox!.width).toBeLessThanOrEqual(
    frameBox!.x + frameBox!.width + 1,
  );
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
