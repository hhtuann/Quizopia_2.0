import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type Request,
  type Response,
} from "@playwright/test";

const gatewayOrigin = "http://localhost:8080";
const mailpitOrigin = "http://localhost:8025";
const refreshUrl = `${gatewayOrigin}/api/auth/refresh`;
const executorUrl = `${gatewayOrigin}/api/auth/me?e2e=executor-refresh-retry`;

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

async function registerAndVerify(
  request: APIRequestContext,
  credentials: { email: string; password: string; username: string },
) {
  const registration = await request.post(
    `${gatewayOrigin}/api/auth/register`,
    {
      data: credentials,
    },
  );
  expect(registration.status()).toBe(202);

  const verificationRequest = await request.post(
    `${gatewayOrigin}/api/auth/email-verification/request`,
    { data: { username: credentials.username } },
  );
  expect(verificationRequest.status()).toBe(202);

  const otp = await waitForVerificationCode(request, credentials.email);
  const confirmation = await request.post(
    `${gatewayOrigin}/api/auth/email-verification/confirm`,
    { data: { otp, username: credentials.username } },
  );
  expect(confirmation.status()).toBe(204);
}

async function storageSnapshot(page: Page) {
  return page.evaluate(() => ({
    cookie: document.cookie,
    localStorageKeys: Object.keys(localStorage),
    sessionStorageKeys: Object.keys(sessionStorage),
  }));
}

function hasBearer(request: Request): boolean {
  return request.headers().authorization?.startsWith("Bearer ") === true;
}

function jwtTiming(token: unknown): { issuedAt: number; expiresAt: number } {
  if (typeof token !== "string") {
    throw new Error(
      "Identity login response does not contain a JWT access token.",
    );
  }
  const payload = token.split(".")[1];
  if (!payload) {
    throw new Error("Identity access token does not have a JWT payload.");
  }

  // Decode only in the test process. Never persist or print token bytes.
  const claims = JSON.parse(
    Buffer.from(payload, "base64url").toString("utf8"),
  ) as {
    iat?: unknown;
    exp?: unknown;
  };
  if (!Number.isSafeInteger(claims.iat) || !Number.isSafeInteger(claims.exp)) {
    throw new Error("Identity JWT lacks valid integer iat/exp claims.");
  }
  return { issuedAt: claims.iat as number, expiresAt: claims.exp as number };
}

test("production authenticated request executor refreshes an expired real token and retries once", async ({
  context,
  page,
  request,
}) => {
  const runId = Date.now();
  const username = `executor${runId}`;
  const email = `${username}@gmail.com`;
  const password = `Executor-real-auth-${runId}-A1!`;

  await registerAndVerify(request, { email, password, username });
  await page.goto("/");
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password").fill(password);
  const loginResponse = page.waitForResponse(
    (response) =>
      response.url() === `${gatewayOrigin}/api/auth/login` &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#status")).toHaveText("login-ready");
  const login = (await (await loginResponse).json()) as {
    accessToken?: unknown;
  };
  const { issuedAt, expiresAt } = jwtTiming(login.accessToken);
  const actualTtlSeconds = expiresAt - issuedAt;
  console.info(
    `Identity JWT timing: iat=${issuedAt}, exp=${expiresAt}, TTL=${actualTtlSeconds}s (no token contents logged).`,
  );
  expect(actualTtlSeconds).toBeGreaterThanOrEqual(4);
  expect(
    actualTtlSeconds,
    "Real Executor expiry E2E requires isolated Identity with IDENTITY_AUTHORIZATION_SERVER_USER_ACCESS_TOKEN_TTL=PT5S. Restart Identity and verify its issued JWT iat/exp.",
  ).toBeLessThanOrEqual(10);

  const loginCookies = await context.cookies(refreshUrl);
  const loginRefreshCookie = loginCookies.find(
    (cookie) => cookie.name === "quizopia_refresh",
  );
  expect(loginRefreshCookie?.httpOnly).toBe(true);
  expect(loginRefreshCookie?.value.length ?? 0).toBeGreaterThan(0);
  const loginRefreshCookieValue = loginRefreshCookie?.value ?? "";

  const beforeExpiryStorage = await storageSnapshot(page);
  expect(beforeExpiryStorage.cookie).not.toContain("quizopia_refresh");
  expect(beforeExpiryStorage.localStorageKeys).toEqual([]);
  expect(beforeExpiryStorage.sessionStorageKeys).toEqual([]);

  // Wait from the actual exp claim. Spring JWT timestamp validation can
  // tolerate up to 60 seconds of clock skew; include a small safety margin.
  const waitUntil = expiresAt * 1000 + 65_000;
  const remainingMs = Math.max(0, waitUntil - Date.now());
  expect(remainingMs).toBeLessThan(90_000);
  await page.waitForTimeout(remainingMs);

  const protectedRequests: Request[] = [];
  const protectedResponses: Response[] = [];
  const refreshRequests: Request[] = [];
  const refreshResponses: Response[] = [];
  const onRequest = (requestEvent: Request) => {
    if (requestEvent.url() === executorUrl) {
      protectedRequests.push(requestEvent);
    } else if (requestEvent.url() === refreshUrl) {
      refreshRequests.push(requestEvent);
    }
  };
  const onResponse = (response: Response) => {
    if (response.url() === executorUrl) {
      protectedResponses.push(response);
    } else if (response.url() === refreshUrl) {
      refreshResponses.push(response);
    }
  };
  page.on("request", onRequest);
  page.on("response", onResponse);

  await page.getByRole("button", { name: "Run protected request" }).click();
  await expect(page.locator("#status")).toHaveText(
    "protected-request-complete",
  );

  page.off("request", onRequest);
  page.off("response", onResponse);

  expect(protectedRequests).toHaveLength(2);
  expect(protectedResponses.map((response) => response.status())).toEqual([
    401, 200,
  ]);
  expect(protectedRequests.every(hasBearer)).toBe(true);

  expect(refreshRequests).toHaveLength(1);
  expect(refreshResponses).toHaveLength(1);
  expect(refreshResponses[0]?.status()).toBe(200);
  const refreshHeaders = await refreshRequests[0]!.allHeaders();
  expect(refreshHeaders.cookie).toContain("quizopia_refresh=");
  expect(refreshHeaders.authorization).toBeUndefined();
  expect(refreshRequests[0]?.postData()).toBeNull();

  await expect(page.locator("#status")).toHaveAttribute(
    "data-create-request-count",
    "2",
  );
  await expect(page.locator("#status")).toHaveAttribute(
    "data-result-kind",
    "response",
  );
  await expect(page.locator("#status")).toHaveAttribute(
    "data-result-status",
    "200",
  );
  await expect(page.locator("#status")).toHaveAttribute(
    "data-same-user",
    "true",
  );

  const rotatedCookies = await context.cookies(refreshUrl);
  const rotatedRefreshCookie = rotatedCookies.find(
    (cookie) => cookie.name === "quizopia_refresh",
  );
  expect(rotatedRefreshCookie?.httpOnly).toBe(true);
  const cookieRotated =
    (rotatedRefreshCookie?.value ?? "") !== loginRefreshCookieValue;
  expect(cookieRotated).toBe(true);

  const afterRefreshStorage = await storageSnapshot(page);
  expect(afterRefreshStorage.cookie).not.toContain("quizopia_refresh");
  expect(afterRefreshStorage.localStorageKeys).toEqual([]);
  expect(afterRefreshStorage.sessionStorageKeys).toEqual([]);

  const browserAuthUrls = [...protectedRequests, ...refreshRequests].map(
    (requestEvent) => requestEvent.url(),
  );
  expect(
    browserAuthUrls.every((url) => url.startsWith(`${gatewayOrigin}/`)),
  ).toBe(true);
  expect(browserAuthUrls.some((url) => url.includes("localhost:8081"))).toBe(
    false,
  );
});
