import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const gatewayOrigin = "http://localhost:8080";
const mailpitOrigin = "http://localhost:8025";
const refreshCookieUrl = `${gatewayOrigin}/api/auth/refresh`;

interface MailpitMessageSummary {
  readonly ID?: string;
  readonly To?: readonly { readonly Address?: string }[];
}

interface MailpitMessageList {
  readonly messages?: readonly MailpitMessageSummary[];
}

interface MailpitMessageDetail {
  readonly Text?: string;
}

async function waitForVerificationCode(
  request: APIRequestContext,
  email: string,
): Promise<string> {
  const deadline = Date.now() + 30_000;

  while (Date.now() < deadline) {
    const listResponse = await request.get(`${mailpitOrigin}/api/v1/messages`);
    expect(listResponse.ok()).toBe(true);
    const list = (await listResponse.json()) as MailpitMessageList;
    const summary = list.messages?.find((message) =>
      message.To?.some((recipient) => recipient.Address === email),
    );

    if (summary?.ID) {
      const messageResponse = await request.get(
        `${mailpitOrigin}/api/v1/message/${encodeURIComponent(summary.ID)}`,
      );
      expect(messageResponse.ok()).toBe(true);
      const message = (await messageResponse.json()) as MailpitMessageDetail;
      const match = message.Text?.match(/six-digit code:\s*(\d{6})/i);
      if (match?.[1]) {
        return match[1];
      }
    }

    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw new Error("Timed out waiting for the verification email in Mailpit.");
}

async function browserStorageSnapshot(page: Page) {
  return page.evaluate(async () => ({
    cookie: document.cookie,
    indexedDbNames:
      "databases" in indexedDB
        ? (await indexedDB.databases()).map((database) => database.name ?? "")
        : [],
    localStorage: Object.keys(localStorage),
    sessionStorage: Object.keys(sessionStorage),
  }));
}

test.describe.configure({ mode: "serial" });
test.setTimeout(120_000);

test("real browser auth traverses Gateway, Identity, PostgreSQL, Redis, and Mailpit", async ({
  context,
  page,
  request,
}) => {
  const runId = Date.now();
  const username = `real${runId}`;
  const email = `${username}@gmail.com`;
  const password = `Local-real-auth-${runId}-A1!`;
  const authRequestUrls: string[] = [];
  let observedAccessToken: string | null = null;

  page.on("request", (requestEvent) => {
    if (requestEvent.url().includes("/api/auth/")) {
      authRequestUrls.push(requestEvent.url());
    }

    const authorization = requestEvent.headers().authorization;
    if (
      requestEvent.url().endsWith("/api/auth/me") &&
      authorization?.startsWith("Bearer ")
    ) {
      observedAccessToken = authorization.slice("Bearer ".length);
    }
  });

  await page.goto("/register");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(
    new RegExp(`/verify-email\\?username=${username}$`),
  );

  const requestVerificationResponse = page.waitForResponse(
    (response) =>
      response.url() ===
        `${gatewayOrigin}/api/auth/email-verification/request` &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Resend code" }).click();
  expect((await requestVerificationResponse).ok()).toBe(true);
  await expect(page.getByRole("status")).toContainText(
    "verification code can be issued",
  );

  const otp = await waitForVerificationCode(request, email);
  const invalidOtp = otp === "000000" ? "111111" : "000000";
  await page.getByLabel("Verification code").fill(invalidOtp);
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Email verification failed" }),
  ).toContainText("Email verification failed");
  await expect(page).toHaveURL(
    new RegExp(`/verify-email\\?username=${username}$`),
  );

  const confirmResponse = page.waitForResponse(
    (response) =>
      response.url() ===
        `${gatewayOrigin}/api/auth/email-verification/confirm` &&
      response.request().method() === "POST",
  );
  await page.getByLabel("Verification code").fill(otp);
  await page.getByRole("button", { name: "Verify email" }).click();
  expect((await confirmResponse).ok()).toBe(true);
  await expect(page).toHaveURL(/\/login$/);

  const afterVerificationCookies = await context.cookies(refreshCookieUrl);
  expect(
    afterVerificationCookies.find(
      (cookie) => cookie.name === "quizopia_refresh",
    ),
  ).toBeUndefined();

  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password").fill("wrong-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "username/email or password is incorrect" }),
  ).toContainText("username/email or password is incorrect");

  const meResponse = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/me` &&
      response.status() === 200,
  );
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  const mePayload = await (await meResponse).json();
  expect(mePayload).toMatchObject({ email, roles: ["STUDENT"], username });
  await expect(page).toHaveURL(/\/app$/);
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
  expect(observedAccessToken).toBeTruthy();

  const afterLoginStorage = await browserStorageSnapshot(page);
  expect(afterLoginStorage.cookie).not.toContain("quizopia_refresh");
  expect(afterLoginStorage.localStorage).not.toContain("accessToken");
  expect(afterLoginStorage.localStorage).not.toContain("refreshToken");
  expect(afterLoginStorage.sessionStorage).not.toContain("accessToken");
  expect(afterLoginStorage.sessionStorage).not.toContain("refreshToken");
  expect(afterLoginStorage.indexedDbNames).toEqual([]);

  const loginCookies = await context.cookies(refreshCookieUrl);
  const refreshCookie = loginCookies.find(
    (cookie) => cookie.name === "quizopia_refresh",
  );
  expect(refreshCookie).toMatchObject({
    httpOnly: true,
    path: "/api/auth",
    sameSite: "Lax",
  });
  expect(refreshCookie?.value).toBeTruthy();
  const firstRefreshCookieValue = refreshCookie?.value;

  const reloadRefreshRequests: import("@playwright/test").Request[] = [];
  const reloadRefreshResponses: import("@playwright/test").Response[] = [];
  const reloadMeResponse = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/me` &&
      response.status() === 200,
  );
  const requestListener = (
    requestEvent: import("@playwright/test").Request,
  ) => {
    if (requestEvent.url() === `${gatewayOrigin}/api/auth/refresh`) {
      reloadRefreshRequests.push(requestEvent);
    }
  };
  const responseListener = (response: import("@playwright/test").Response) => {
    if (response.url() === `${gatewayOrigin}/api/auth/refresh`) {
      reloadRefreshResponses.push(response);
    }
  };
  page.on("request", requestListener);
  page.on("response", responseListener);
  await page.reload();
  expect((await reloadMeResponse).ok()).toBe(true);
  page.off("request", requestListener);
  page.off("response", responseListener);
  await expect(
    page.getByRole("heading", { name: "Your Quizopia workspace" }),
  ).toBeVisible();
  expect(reloadRefreshRequests).toHaveLength(1);
  expect(reloadRefreshResponses).toHaveLength(1);
  const refreshRequestHeaders = await reloadRefreshRequests[0].allHeaders();
  expect(refreshRequestHeaders.cookie).toContain("quizopia_refresh=");
  expect(refreshRequestHeaders.authorization).toBeUndefined();
  const refreshResponseHeaders = await reloadRefreshResponses[0].allHeaders();
  expect(refreshResponseHeaders["set-cookie"]).toContain("quizopia_refresh=");

  const rotatedCookies = await context.cookies(refreshCookieUrl);
  const rotatedRefreshCookie = rotatedCookies.find(
    (cookie) => cookie.name === "quizopia_refresh",
  );
  expect(rotatedRefreshCookie?.httpOnly).toBe(true);
  expect(rotatedRefreshCookie?.value).toBeTruthy();
  expect(rotatedRefreshCookie?.value).not.toBe(firstRefreshCookieValue);

  const unauthorizedProtectedResponse = await page.evaluate(async () =>
    fetch("http://localhost:8080/api/auth/me", {
      headers: { Authorization: "Bearer real-e2e-invalid-token" },
    }).then((response) => response.status),
  );
  expect(unauthorizedProtectedResponse).toBe(401);

  const logoutResponse = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/logout` &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: new RegExp(`Open user menu for ${username}`) })
    .click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  expect((await logoutResponse).ok()).toBe(true);
  await expect(
    page.getByRole("heading", { name: "Sign in to continue" }),
  ).toBeVisible();

  const afterLogoutCookies = await context.cookies(refreshCookieUrl);
  expect(
    afterLogoutCookies.find((cookie) => cookie.name === "quizopia_refresh"),
  ).toBeUndefined();
  expect(await browserStorageSnapshot(page)).toMatchObject({
    cookie: "",
    indexedDbNames: [],
    localStorage: [],
    sessionStorage: [],
  });

  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Sign in to continue" }),
  ).toBeVisible();
  expect(
    authRequestUrls.every((url) => url.startsWith(`${gatewayOrigin}/`)),
  ).toBe(true);
  expect(authRequestUrls.some((url) => url.includes("localhost:8081"))).toBe(
    false,
  );
});

test("real teacher preserves published A/B history while current draft advances to C", async ({
  page,
  request,
}) => {
  const runId = Date.now();
  const username = `teacher${runId}`;
  const email = `${username}@gmail.com`;
  const password = `Local-teacher-${runId}-A1!`;
  const title = `Version history quiz ${runId}`;
  const sourceA = [
    "Câu 1 [SINGLE_CHOICE]: ALPHA published snapshot asks about HTTP?",
    "*A. ALPHA HTTP answer",
    "B. ALPHA FTP distractor",
    "C. ALPHA SSH distractor",
    "D. ALPHA SMTP distractor",
    "",
    "Lời giải: ALPHA explanation belongs only to version one.",
  ].join("\n");
  const sourceB = [
    "Câu 1 [SINGLE_CHOICE]: BRAVO published snapshot asks about TLS?",
    "A. BRAVO FTP distractor",
    "*B. BRAVO TLS answer",
    "C. BRAVO SSH distractor",
    "D. BRAVO SMTP distractor",
    "",
    "Lời giải: BRAVO explanation belongs only to version two.",
  ].join("\n");
  const sourceC = [
    "Câu 1 [SINGLE_CHOICE]: CHARLIE unpublished draft asks about DNS?",
    "A. CHARLIE HTTP distractor",
    "B. CHARLIE FTP distractor",
    "*C. CHARLIE DNS answer",
    "D. CHARLIE SMTP distractor",
    "",
    "Lời giải: CHARLIE remains only in the mutable current draft.",
  ].join("\n");

  await page.goto("/register");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(
    new RegExp(`/verify-email\\?username=${username}$`),
  );

  await page.getByRole("button", { name: "Resend code" }).click();
  await expect(page.getByRole("status")).toContainText(
    "verification code can be issued",
  );

  const otp = await waitForVerificationCode(request, email);
  await page.getByLabel("Verification code").fill(otp);
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(page).toHaveURL(/\/login$/);

  const initialMe = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/me` &&
      response.status() === 200,
  );
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  expect(await (await initialMe).json()).toMatchObject({
    roles: ["STUDENT"],
    username,
  });
  await expect(page).toHaveURL(/\/app$/);

  await page
    .getByRole("button", { name: new RegExp(`Open user menu for ${username}`) })
    .click();
  const enablementResponse = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/teacher-enablement` &&
      response.request().method() === "POST",
  );
  const authoritativeTeacherMe = page.waitForResponse(async (response) => {
    if (
      response.url() !== `${gatewayOrigin}/api/auth/me` ||
      response.status() !== 200
    ) {
      return false;
    }
    const payload = (await response.json()) as { roles?: string[] };
    return (
      payload.roles?.includes("STUDENT") === true &&
      payload.roles.includes("TEACHER")
    );
  });
  await page.getByRole("menuitem", { name: "Register as teacher" }).click();
  expect((await enablementResponse).status()).toBe(204);
  const teacherMePayload = await (await authoritativeTeacherMe).json();
  expect(teacherMePayload).toMatchObject({
    roles: ["STUDENT", "TEACHER"],
    username,
  });
  await expect(page.getByRole("status")).toContainText(
    "Teacher access is ready",
  );
  await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
  await page.getByRole("link", { name: "Quiz authoring" }).click();
  await expect(page).toHaveURL(/\/app\/quizzes$/);

  await page.getByRole("link", { name: "Create quiz" }).click();
  await expect(page).toHaveURL(/\/app\/quizzes\/[0-9a-f-]+$/);
  const quizUrl = page.url();
  await page.getByRole("textbox", { name: "Quiz title" }).fill(title);
  await page
    .getByRole("textbox", { name: "Quiz Markdown source" })
    .fill(sourceA);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved")).toBeVisible();

  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Open the Teaching workspace" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Switch to Teaching" }).click();
  await expect(page).toHaveURL(quizUrl);
  await expect(page.getByRole("textbox", { name: "Quiz title" })).toHaveValue(
    title,
  );
  await expect(
    page.getByRole("textbox", { name: "Quiz Markdown source" }),
  ).toHaveValue(sourceA);

  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByLabel("Description").fill("Real Wave 2 journey");
  await page.getByRole("button", { name: "Publish QuizVersion" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Publish complete" }),
  ).toContainText("Published immutable version 1");

  const editor = page.getByRole("textbox", { name: "Quiz Markdown source" });
  await editor.fill(sourceB);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByLabel("Description").fill("Real immutable version B");
  await page.getByRole("button", { name: "Publish QuizVersion" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Publish complete" }),
  ).toContainText("Published immutable version 2");

  await editor.fill(sourceC);
  const saveCResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/draft") &&
      response.request().method() === "PUT" &&
      response.status() === 200,
  );
  await page.getByRole("button", { name: "Save" }).click();
  const savedDraftC = await (await saveCResponsePromise).json();
  expect(savedDraftC.authoringSource).toBe(sourceC);
  await expect(editor).toHaveValue(sourceC);

  const historyResponsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname.endsWith("/versions") &&
      response.request().method() === "GET" &&
      response.status() === 200,
  );
  await page.getByRole("button", { name: "Published versions" }).click();
  const historyDialog = page.getByRole("dialog", {
    name: "Published versions",
  });
  const historyPayload = await (await historyResponsePromise).json();
  expect(
    historyPayload.items.map(
      (item: { versionNumber: number }) => item.versionNumber,
    ),
  ).toEqual([2, 1]);
  expect(
    historyPayload.items.every(
      (item: Record<string, unknown>) => !("sourceSnapshot" in item),
    ),
  ).toBe(true);

  const history = page.getByRole("list", { name: "Published version history" });
  const versionButtons = history.getByRole("button");
  await expect(versionButtons).toHaveCount(2);
  expect((await versionButtons.nth(0).textContent()) ?? "").toContain(
    "Version 2",
  );
  expect((await versionButtons.nth(1).textContent()) ?? "").toContain(
    "Version 1",
  );

  const versionOneResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/versions/1") &&
      response.request().method() === "GET" &&
      response.status() === 200,
  );
  await history.getByRole("button", { name: /Version 1/ }).click();
  const versionOnePayload = await (await versionOneResponsePromise).json();
  expect(versionOnePayload.sourceSnapshot).toBe(sourceA);
  await expect(
    historyDialog.getByText(/ALPHA published snapshot/),
  ).toBeVisible();
  await expect(historyDialog.getByText(/BRAVO published snapshot/)).toHaveCount(
    0,
  );
  await expect(
    historyDialog.getByText(/CHARLIE unpublished draft/),
  ).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Publish", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: /Marked correct/ }),
  ).toHaveCount(0);

  const versionTwoResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/versions/2") &&
      response.request().method() === "GET" &&
      response.status() === 200,
  );
  await history.getByRole("button", { name: /Version 2/ }).click();
  const versionTwoPayload = await (await versionTwoResponsePromise).json();
  expect(versionTwoPayload.sourceSnapshot).toBe(sourceB);
  await expect(
    historyDialog.getByText(/BRAVO published snapshot/),
  ).toBeVisible();
  await expect(historyDialog.getByText(/ALPHA published snapshot/)).toHaveCount(
    0,
  );
  await expect(
    historyDialog.getByText(/CHARLIE unpublished draft/),
  ).toHaveCount(0);

  await historyDialog
    .getByRole("button", { name: "Close published versions" })
    .click();
  await expect(editor).toHaveValue(sourceC);

  await page.getByRole("button", { name: "Published versions" }).click();
  await history.getByRole("button", { name: /Version 1/ }).click();
  await expect(
    historyDialog.getByText(/ALPHA published snapshot/),
  ).toBeVisible();
  await history.getByRole("button", { name: /Version 2/ }).click();
  await expect(
    historyDialog.getByText(/BRAVO published snapshot/),
  ).toBeVisible();

  expect(await browserStorageSnapshot(page)).toMatchObject({
    indexedDbNames: [],
    localStorage: [],
    sessionStorage: [],
  });
});
