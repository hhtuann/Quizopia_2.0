import { expect, test, type Page } from "@playwright/test";

const quizId = "8ad4c564-3c27-4e6d-91aa-a004334aa8f8";

async function mockAnonymous(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({ status: 401, body: "{}" }),
  );
}

async function mockTeacher(page: Page) {
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        accessToken: "scrollbar-e2e-access",
        expiresIn: 300,
        tokenType: "Bearer",
      }),
    }),
  );
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        email: "teacher@gmail.com",
        id: "a7a4c564-3c27-4e6d-91aa-a004334aa8f9",
        roles: ["STUDENT", "TEACHER"],
        username: "teacher",
      }),
    }),
  );
}

async function switchToTeaching(page: Page) {
  const userMenu = page.getByRole("button", { name: /Open user menu/ });
  if ((await userMenu.count()) > 0) {
    await userMenu.click();
    await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
  } else {
    await page.getByRole("button", { name: "Switch to Teaching" }).click();
  }
}

const longSource = Array.from({ length: 45 }, (_, i) =>
  [
    `Câu ${i + 1} [SINGLE_CHOICE]: Question ${i + 1} ${"long ".repeat(25)}`,
    "*A. Yes",
    "B. No",
    "C. Maybe",
    "D. Other",
  ].join("\n"),
).join("\n");

async function mockDraft(page: Page, source = longSource) {
  await page.route(`**/api/quizzes/${quizId}/draft`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        quizId,
        title: "Scrollable editor",
        description: "Isolated scrollbar test",
        authoringSource: source,
        createdAt: "2026-10-08T01:00:00Z",
        updatedAt: "2026-10-08T04:00:00Z",
      }),
    }),
  );
}

for (const { width, height } of [
  { width: 375, height: 812 },
  { width: 768, height: 1024 },
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
  { width: 1600, height: 900 },
  { width: 1440, height: 660 },
]) {
  test(`auth scrollbar stays hidden but usable at ${width}x${height}`, async ({
    page,
  }, testInfo) => {
    await mockAnonymous(page);
    await page.setViewportSize({ width, height });

    for (const route of ["login", "register", "verify-email"]) {
      await page.goto(`/${route}`);
      const state = await page
        .getByTestId("auth-form-scroll")
        .evaluate((element) => ({
          documentScrollbar: getComputedStyle(document.documentElement)
            .scrollbarWidth,
          gutter: getComputedStyle(document.documentElement).scrollbarGutter,
          formScrollbar: getComputedStyle(element).scrollbarWidth,
          formGutter: getComputedStyle(element).scrollbarGutter,
          formOverflow: getComputedStyle(element).overflowY,
          viewportOverflow: document.documentElement.scrollWidth > innerWidth,
        }));
      expect(state.documentScrollbar).toBe("none");
      expect(state.formScrollbar).toBe("none");
      expect(state.gutter).toBe("auto");
      expect(state.formGutter).toBe("auto");
      expect(state.viewportOverflow).toBe(false);

      if (width < 1024) {
        expect(state.formOverflow).not.toBe("auto");
      }
      if (route === "register") {
        if (width >= 1024) {
          expect(state.formOverflow).toBe("auto");
          const scroller = page.getByTestId("auth-form-scroll");
          const welcome = page.getByTestId("auth-welcome");
          const before = await welcome.boundingBox();
          await scroller.evaluate(
            (node) => (node.scrollTop = node.scrollHeight),
          );
          expect(
            Math.abs((await welcome.boundingBox())!.y - before!.y),
          ).toBeLessThan(1);
          if (height === 660) {
            expect(
              await scroller.evaluate((node) => node.scrollTop),
            ).toBeGreaterThan(0);
            await expect(
              page.getByRole("button", { name: "Create account" }),
            ).toBeInViewport();
          }
        } else {
          await page.evaluate(() =>
            window.scrollTo(0, document.body.scrollHeight),
          );
          if (height === 812)
            expect(await page.evaluate(() => scrollY)).toBeGreaterThan(0);
        }
      }
      await page.screenshot({
        path: testInfo.outputPath(`scrollbar-${route}-${width}x${height}.png`),
      });
    }
  });
}

test("document scrollbar uses gradient thumb and stable root gutter", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const result = await page.evaluate(() => {
    const root = document.documentElement;
    const read = () => ({
      gutter: getComputedStyle(root).scrollbarGutter,
      scrollbar: getComputedStyle(root, "::-webkit-scrollbar").width,
      thumb: getComputedStyle(root, "::-webkit-scrollbar-thumb")
        .backgroundImage,
      width: document.body.getBoundingClientRect().width,
      overflow: root.scrollWidth > innerWidth,
      scrollable: root.scrollHeight > innerHeight,
    });
    const initial = read();
    root.style.overflowY = "hidden";
    const hidden = read();
    root.style.overflowY = "auto";
    const restored = read();
    root.style.removeProperty("overflow-y");
    return { initial, hidden, restored };
  });
  expect(result.initial.gutter).toBe("stable");
  expect(result.initial.scrollbar).toBe("7px");
  // The browser may leave the native thumb hovered (e.g. when the pointer
  // starts over the scrollbar in CI). Both authored states are intentional.
  // Require a full gradient with exactly the default OR hover token pair.
  expect(result.initial.thumb).toMatch(
    /^linear-gradient\((?:180deg,\s*)?rgb\(\d+, \d+, \d+\), rgb\(\d+, \d+, \d+\)\)$/,
  );
  const stops = result.initial.thumb.match(/rgb\(\d+, \d+, \d+\)/g);
  expect([
    ["rgb(79, 70, 229)", "rgb(124, 58, 237)"], // primary → secondary
    ["rgb(67, 56, 202)", "rgb(109, 40, 217)"], // hover tokens
  ]).toContainEqual(stops);
  expect(result.initial.scrollable).toBe(true);
  expect(Math.abs(result.initial.width - result.hidden.width)).toBeLessThan(1);
  expect(Math.abs(result.initial.width - result.restored.width)).toBeLessThan(
    1,
  );
  expect(result.initial.overflow).toBe(false);
  await page.screenshot({ path: testInfo.outputPath("branded-landing.png") });
});

test("Markdown source and preview scroll independently with branded scrollbars", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockTeacher(page);
  const source = longSource;
  await mockDraft(page);
  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);

  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  const preview = page.getByRole("region", { name: "Live quiz preview" });
  await expect(editor).toHaveValue(source);
  await expect(preview).toBeVisible();
  const geometry = await editor.evaluate((node) => ({
    editorScrollbar: getComputedStyle(node).scrollbarGutter,
    sourceOverflow: node.scrollHeight > node.clientHeight,
    thumb: getComputedStyle(node, "::-webkit-scrollbar-thumb").backgroundImage,
  }));
  expect(geometry.editorScrollbar).toBe("auto");
  expect(geometry.sourceOverflow).toBe(true);
  expect(geometry.thumb).toContain("linear-gradient");
  await editor.evaluate((node) => {
    node.scrollTop = 168;
    node.scrollLeft = 40;
    node.dispatchEvent(new Event("scroll"));
  });
  await expect
    .poll(() =>
      page.getByTestId("quiz-markdown-highlight-layer").getAttribute("style"),
    )
    .toContain("translate(-40px, -168px)");
  expect(await editor.inputValue()).toBe(source);
  const previewState = await preview.evaluate((node) => ({
    gutter: getComputedStyle(node).scrollbarGutter,
    scrollable: node.scrollHeight > node.clientHeight,
    thumb: getComputedStyle(node, "::-webkit-scrollbar-thumb").backgroundImage,
  }));
  expect(previewState.gutter).toBe("stable");
  expect(previewState.scrollable).toBe(true);
  expect(previewState.thumb).toContain("linear-gradient");
  await preview.evaluate((node) => (node.scrollTop = 200));
  expect(await preview.evaluate((node) => node.scrollTop)).toBeGreaterThan(0);
  expect(await editor.evaluate((node) => node.scrollTop)).toBe(168);
  await page.screenshot({
    path: testInfo.outputPath("branded-markdown-and-preview.png"),
  });
});

test("long Quiz Library uses stable branded document scrolling", async ({
  page,
}, testInfo) => {
  await mockTeacher(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.route("**/api/quizzes", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: Array.from({ length: 35 }, (_, index) => ({
          quizId: `8ad4c564-3c27-4e6d-91aa-${String(index).padStart(12, "0")}`,
          title: `Scrollable quiz ${index + 1}`,
          description: `Teaching quiz number ${index + 1}`,
          createdAt: "2026-10-08T01:00:00Z",
          updatedAt: "2026-10-08T04:00:00Z",
          latestVersionNumber: 1,
        })),
        nextCursor: null,
      }),
    }),
  );
  await page.goto("/app/quizzes");
  await switchToTeaching(page);
  await expect(
    page.getByRole("link", { name: "Scrollable quiz 35" }),
  ).toBeVisible();
  const before = await page.evaluate(() => ({
    top: document.documentElement.scrollTop,
    max: document.documentElement.scrollHeight - innerHeight,
    gutter: getComputedStyle(document.documentElement).scrollbarGutter,
    thumb: getComputedStyle(
      document.documentElement,
      "::-webkit-scrollbar-thumb",
    ).backgroundImage,
    overflow: document.documentElement.scrollWidth > innerWidth,
  }));
  expect(before.max).toBeGreaterThan(100);
  expect(before.gutter).toBe("stable");
  expect(before.thumb).toContain("linear-gradient");
  expect(before.overflow).toBe(false);
  await page.mouse.move(250, 400);
  await page.mouse.wheel(0, 800);
  await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(0);
  await page.keyboard.press("PageDown");
  await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(500);
  await page.screenshot({
    path: testInfo.outputPath("branded-long-library-mobile.png"),
  });
});

test("Published Versions scroll panes retain draft and stable dialog geometry", async ({
  page,
}, testInfo) => {
  await mockTeacher(page);
  await mockDraft(page);
  await page.setViewportSize({ width: 1280, height: 720 });
  const versions = Array.from({ length: 22 }, (_, index) => ({
    id: `e7b14962-3a2e-4d2d-926a-${String(index).padStart(12, "0")}`,
    quizId,
    versionNumber: 22 - index,
    titleSnapshot: `Snapshot ${22 - index}`,
    descriptionSnapshot: "Published fixture",
    contentSchemaVersion: 1,
    createdAt: "2026-10-08T01:30:00Z",
  }));
  await page.route(`**/api/quizzes/${quizId}/versions**`, (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const number = Number(pathname.split("/").at(-1));
    const summary = versions.find((item) => item.versionNumber === number);
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        pathname.endsWith("/versions")
          ? { items: versions, nextCursor: null }
          : {
              ...summary,
              sourceSnapshot: longSource,
              structuredContent: { questions: [] },
            },
      ),
    });
  });
  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await expect(editor).toHaveValue(longSource);
  await page.getByRole("button", { name: "Published versions" }).click();
  const dialog = page.getByRole("dialog", { name: "Published versions" });
  const history = dialog.getByRole("list", {
    name: "Published version history",
  });
  await expect(history.getByRole("button")).toHaveCount(22);
  const aside = history.locator("xpath=parent::aside");
  const detail = dialog.locator("main");
  const initialDialog = await dialog.boundingBox();
  const asideGeometry = await aside.evaluate((node) => ({
    overflow: node.scrollHeight > node.clientHeight,
    gutter: getComputedStyle(node).scrollbarGutter,
    thumb: getComputedStyle(node, "::-webkit-scrollbar-thumb").backgroundImage,
  }));
  expect(asideGeometry.overflow).toBe(true);
  expect(asideGeometry.gutter).toBe("stable");
  expect(asideGeometry.thumb).toContain("linear-gradient");
  await aside.hover();
  await page.mouse.wheel(0, 900);
  await expect
    .poll(() => aside.evaluate((node) => node.scrollTop))
    .toBeGreaterThan(0);
  await history.getByRole("button", { name: /Version 22/ }).click();
  await expect(dialog.getByText("Immutable version 22")).toBeVisible();
  await expect
    .poll(() =>
      detail.evaluate((node) => node.scrollHeight > node.clientHeight),
    )
    .toBe(true);
  await detail.evaluate((node) => (node.scrollTop = 300));
  expect(await detail.evaluate((node) => node.scrollTop)).toBeGreaterThan(0);
  expect(
    await detail.evaluate((node) => getComputedStyle(node).scrollbarGutter),
  ).toBe("stable");
  const finalDialog = await dialog.boundingBox();
  expect(Math.abs(finalDialog!.x - initialDialog!.x)).toBeLessThan(1);
  expect(Math.abs(finalDialog!.width - initialDialog!.width)).toBeLessThan(1);
  await page.screenshot({
    path: testInfo.outputPath("branded-scrollable-published-versions.png"),
  });
  await page.getByRole("button", { name: "Close published versions" }).click();
  await expect(editor).toHaveValue(longSource);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});

test("mobile editor and preview switching preserves scrollable source", async ({
  page,
}) => {
  await mockTeacher(page);
  await mockDraft(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`/app/quizzes/${quizId}`);
  await switchToTeaching(page);
  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await expect(editor).toHaveValue(longSource);
  await editor.evaluate((node) => (node.scrollTop = 220));
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  const preview = page.getByRole("region", { name: "Live quiz preview" });
  await expect(preview).toBeVisible();
  await page.getByRole("button", { name: "Editor", exact: true }).click();
  await expect(editor).toBeVisible();
  await expect(editor).toHaveValue(longSource);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});
