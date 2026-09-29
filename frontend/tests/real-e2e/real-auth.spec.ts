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
  await page.getByRole("button", { name: "Sign out" }).click();
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
