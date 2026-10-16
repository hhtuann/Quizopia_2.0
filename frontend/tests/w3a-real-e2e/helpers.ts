import { randomBytes, randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import { resolve } from "node:path";
import { promisify } from "node:util";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type Response,
  type TestInfo,
} from "@playwright/test";
import type { OwnerView } from "../../features/assessments/api/publication-contract.generated";

function loopbackOrigin(input: string): string {
  const url = new URL(input);
  if (
    url.protocol !== "http:" ||
    !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  )
    throw new Error("Real acceptance requires an isolated loopback origin.");
  return url.origin;
}

export const gateway = loopbackOrigin(
  process.env.W3A_REAL_GATEWAY_URL ?? "http://localhost:8080",
);
const mailpit = loopbackOrigin(
  process.env.W3A_REAL_MAILPIT_URL ?? "http://127.0.0.1:8025",
);
const publicationBase = "/api/assessments/publications";

export function evidence(info: TestInfo, description: string) {
  // Callers supply static descriptions/status numbers only, never raw payloads.
  info.annotations.push({ type: "evidence", description });
}

export function assertStatus(
  actual: number,
  expected: number,
  operation: string,
) {
  if (actual !== expected)
    evidence(
      test.info(),
      `${operation}: expected HTTP ${expected}, received HTTP ${actual}.`,
    );
  expect(actual, operation).toBe(expected);
}

export class BrowserActor {
  private accessToken = "";
  private credentials: { username: string; password: string } | null = null;
  private readonly sensitiveValues = new Set<string>();
  private readonly networkUrls: string[] = [];
  private readonly consoleMessages: string[] = [];
  readonly authRequests: string[] = [];

  constructor(readonly page: Page) {
    page.on("request", (request) => {
      this.networkUrls.push(request.url());
      if (new URL(request.url()).pathname.startsWith("/api/auth/"))
        this.authRequests.push(new URL(request.url()).pathname);
      if (request.url().startsWith(`${gateway}/`)) {
        const authorization = request.headers().authorization;
        if (
          authorization?.startsWith("Bearer ") &&
          authorization.split(".").length === 3
        ) {
          this.accessToken = authorization.slice(7);
          this.sensitiveValues.add(this.accessToken);
        }
      }
    });
    page.on("console", (message) => this.consoleMessages.push(message.text()));
  }

  rememberSecret(value: string) {
    if (value) this.sensitiveValues.add(value);
  }

  setCredentials(username: string, password: string) {
    this.credentials = { username, password };
    this.rememberSecret(password);
  }

  async signInOn(page: Page): Promise<BrowserActor> {
    if (!this.credentials)
      throw new Error("Disposable account credentials missing.");
    const actor = new BrowserActor(page);
    actor.setCredentials(this.credentials.username, this.credentials.password);
    // The real user-menu logout performs Next router.replace('/login').
    // Wait for that navigation; clicking its transient boundary link races it.
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel("Username or email").fill(this.credentials.username);
    await page.getByLabel("Password").fill(this.credentials.password);
    const me = page.waitForResponse(
      (response) =>
        response.url() === `${gateway}/api/auth/me` &&
        response.status() === 200,
    );
    await page.getByRole("button", { name: "Sign in" }).click();
    await me;
    await expect(page).toHaveURL(/\/app$/);
    return actor;
  }

  async fetch<T>(path: string, method = "GET", body?: unknown, key?: string) {
    expect(path.startsWith("/api/") || path === "/classrooms").toBe(true);
    expect(
      Boolean(this.accessToken),
      "authoritative browser session exists",
    ).toBe(true);
    return this.page.evaluate(
      async ({ url, token, method, body, key }) => {
        const response = await fetch(url, {
          method,
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: "application/json",
            ...(body === undefined
              ? {}
              : { "Content-Type": "application/json" }),
            ...(key ? { "Idempotency-Key": key } : {}),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        });
        const text = await response.text();
        return {
          status: response.status,
          cacheControl: response.headers.get("cache-control"),
          body: text ? JSON.parse(text) : null,
        };
      },
      { url: `${gateway}${path}`, token: this.accessToken, method, body, key },
    ) as Promise<{ status: number; cacheControl: string | null; body: T }>;
  }

  async owner(id: string): Promise<OwnerView> {
    const response = await this.fetch<OwnerView>(`${publicationBase}/${id}`);
    assertStatus(response.status, 200, "fresh authoritative owner GET");
    expect(response.cacheControl?.includes("no-store")).toBe(true);
    if (response.body.privateLocator)
      this.rememberSecret(response.body.privateLocator);
    return response.body;
  }

  async assertConfidentiality() {
    const cookies = await this.page
      .context()
      .cookies(`${gateway}/api/auth/refresh`);
    const refresh = cookies.find(
      (cookie) => cookie.name === "quizopia_refresh",
    );
    expect(Boolean(refresh?.value)).toBe(true);
    expect(refresh?.httpOnly).toBe(true);
    expect(refresh?.sameSite).toBe("Lax");
    expect(refresh?.path).toBe("/api/auth");
    if (refresh?.value) this.rememberSecret(refresh.value);
    const snapshot = await this.page.evaluate(async () => ({
      cookie: document.cookie,
      local: JSON.stringify(localStorage),
      session: JSON.stringify(sessionStorage),
      links: [...document.querySelectorAll("a")].map((link) => link.href),
      indexedDbNames:
        "databases" in indexedDB
          ? (await indexedDB.databases()).map((database) => database.name ?? "")
          : [],
    }));
    expect(snapshot.cookie.includes("quizopia_refresh")).toBe(false);
    expect(snapshot.indexedDbNames).toEqual([]);
    const nonSecretSurfaces = [
      this.page.url(),
      ...this.networkUrls,
      ...this.consoleMessages,
      ...snapshot.links,
      snapshot.local,
      snapshot.session,
      snapshot.cookie,
    ];
    expect(
      [...this.sensitiveValues].some((secret) =>
        nonSecretSurfaces.some((surface) => surface.includes(secret)),
      ),
      "credentials and locators stay absent from URLs, console and storage",
    ).toBe(false);
    const payload = JSON.parse(
      Buffer.from(this.accessToken.split(".")[1], "base64url").toString(),
    ) as { principal_type?: string };
    expect(payload.principal_type).toBe("USER");
    expect(
      this.networkUrls.some((url) => new URL(url).pathname === "/oauth2/token"),
    ).toBe(false);
    expect(
      [...this.sensitiveValues]
        .filter((value) => value.split(".").length === 3)
        .every((token) => {
          try {
            const claims = JSON.parse(
              Buffer.from(token.split(".")[1], "base64url").toString(),
            ) as { principal_type?: string; exp?: number; iat?: number };
            return (
              claims.principal_type === "USER" &&
              typeof claims.exp === "number" &&
              typeof claims.iat === "number" &&
              claims.exp - claims.iat === 300
            );
          } catch {
            return false;
          }
        }),
    ).toBe(true);
  }
}

async function verificationCode(request: APIRequestContext, email: string) {
  let code = "";
  await expect
    .poll(
      async () => {
        const response = await request.get(`${mailpit}/api/v1/messages`);
        assertStatus(response.status(), 200, "isolated Mailpit messages");
        const list = (await response.json()) as {
          messages?: { ID: string; To?: { Address: string }[] }[];
        };
        const message = list.messages?.find((item) =>
          item.To?.some((recipient) => recipient.Address === email),
        );
        if (!message) return false;
        const detail = await request.get(
          `${mailpit}/api/v1/message/${message.ID}`,
        );
        assertStatus(detail.status(), 200, "isolated Mailpit delivery");
        const text = ((await detail.json()) as { Text?: string }).Text;
        code = text?.match(/six-digit code:\s*(\d{6})/i)?.[1] ?? "";
        return code.length === 6;
      },
      { timeout: 30_000, intervals: [200, 400, 800] },
    )
    .toBe(true);
  return code;
}

export async function registerTeacher(
  page: Page,
  request: APIRequestContext,
  info: TestInfo,
  enableTeacher = true,
) {
  const actor = new BrowserActor(page);
  const suffix = randomBytes(8).toString("hex");
  const username = `w3a${suffix}`;
  const email = `${username}@gmail.com`;
  const password = `W3a-${randomBytes(24).toString("hex")}!A1`;
  actor.rememberSecret(password);
  actor.setCredentials(username, password);
  await page.goto("/register");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/verify-email\?username=/);
  const issued = page.waitForResponse(
    (response) =>
      response.url() === `${gateway}/api/auth/email-verification/request` &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Resend code" }).click();
  assertStatus((await issued).status(), 202, "real verification issuance");
  const code = await verificationCode(request, email);
  actor.rememberSecret(code);
  await page
    .getByRole("textbox", {
      name: "Verification code digit 1 of 6",
      exact: true,
    })
    .fill(code);
  await page.getByRole("button", { name: "Verify email" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password").fill(password);
  const me = page.waitForResponse(
    (response) =>
      response.url() === `${gateway}/api/auth/me` && response.status() === 200,
  );
  await page.getByRole("button", { name: "Sign in" }).click();
  await me;
  await expect(page).toHaveURL(/\/app$/);
  if (!enableTeacher) {
    evidence(
      info,
      "Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.",
    );
    return actor;
  }
  await enableTeacherUi(page, actor);
  evidence(
    info,
    "Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.",
  );
  return actor;
}

export async function enableTeacherUi(page: Page, actor: BrowserActor) {
  await page.getByRole("button", { name: /Open user menu for/ }).click();
  const enabled = page.waitForResponse(
    (response) => response.url() === `${gateway}/api/auth/teacher-enablement`,
  );
  await page.getByRole("menuitem", { name: "Register as teacher" }).click();
  assertStatus((await enabled).status(), 204, "real Teacher enablement");
  await expect(page.getByRole("status")).toContainText(
    "Teacher access is ready",
  );
  await page.getByRole("menuitem", { name: "Switch to Teaching" }).click();
  const readinessId = randomUUID();
  let observedUnavailable = false;
  await expect
    .poll(
      async () => {
        const ready = await actor.fetch<{ code?: string }>(
          `${publicationBase}/${readinessId}`,
        );
        if (ready.status === 503) {
          expect(ready.body.code).toBe("AUTHORITY_UNAVAILABLE");
          observedUnavailable = true;
        } else {
          assertStatus(
            ready.status,
            404,
            "protected Teacher admission fixture readiness",
          );
          expect(ready.body.code).toBe("PUBLICATION_NOT_FOUND");
        }
        return ready.status;
      },
      { timeout: 10_000, intervals: [200, 400, 800] },
    )
    .toBe(404);
  if (observedUnavailable)
    evidence(
      test.info(),
      "Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.",
    );
}

export async function publishExactSource(page: Page) {
  await page.getByRole("link", { name: "Quiz authoring", exact: true }).click();
  await page.getByRole("link", { name: "Create quiz" }).click();
  await expect(page).toHaveURL(/\/app\/quizzes\/[0-9a-f-]+$/);
  const quizId = new URL(page.url()).pathname.split("/").at(-1)!;
  await page
    .getByRole("textbox", { name: "Quiz title" })
    .fill("Isolated W3-A pinned source");
  await page
    .getByRole("textbox", { name: "Quiz Markdown source" })
    .fill(
      "Câu 1 [SINGLE_CHOICE]: Which protocol serves web pages?\n*A. HTTP\nB. FTP\nC. SSH\nD. SMTP",
    );
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page
    .getByLabel("Description")
    .fill("Disposable real integration source");
  await page.getByRole("button", { name: "Publish QuizVersion" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Publish complete" }),
  ).toContainText("Published immutable version 1");
  await page.getByRole("button", { name: "Published versions" }).click();
  await page
    .getByRole("list", { name: "Published version history" })
    .getByRole("button", { name: /Version 1/ })
    .click();
  await page
    .getByRole("button", { name: "Create assessment from version 1" })
    .click();
  await expect(page).toHaveURL(/\/app\/assessments\/new\?/);
  const query = new URL(page.url()).searchParams;
  expect(query.get("quizId") === quizId).toBe(true);
  expect(query.get("versionNumber")).toBe("1");
  const quizVersionId = query.get("quizVersionId")!;
  expect(/^[0-9a-f-]{36}$/.test(quizVersionId)).toBe(true);
  return { quizId, quizVersionId, versionNumber: 1 };
}

export async function confirm(page: Page, label: string) {
  await page
    .getByRole("dialog")
    .getByRole("button", { name: label, exact: true })
    .click();
}

export function commandResponse(
  page: Page,
  suffix: string,
  method: string,
): Promise<Response> {
  return page.waitForResponse(
    (response) =>
      response.url().startsWith(`${gateway}${publicationBase}`) &&
      new URL(response.url()).pathname.endsWith(suffix) &&
      response.request().method() === method,
  );
}

export function utcField(offsetMinutes: number, fraction: string) {
  return `${new Date(Date.now() + offsetMinutes * 60_000).toISOString().slice(0, 19)}.${fraction}`;
}

export { publicationBase };

export async function fixtureControl(
  action:
    | "pause-quiz"
    | "resume-quiz"
    | "pause-gateway"
    | "resume-gateway"
    | "seed-membership"
    | "pause-assessment"
    | "resume-assessment"
    | "quiz-pending-request"
    | "creation-receipt-count",
  ...ids: string[]
) {
  const state = process.env.W3A_REAL_STATE_DIRECTORY;
  if (!state)
    throw new Error(
      "Owned disposable runtime state is required for fault/membership acceptance.",
    );
  const result = await promisify(execFile)(
    "python3",
    [
      resolve(process.cwd(), "../scripts/local/w3a-real-runtime.py"),
      action,
      state,
      ...ids,
    ],
    { timeout: 15_000, env: { ...process.env, PYTHONDONTWRITEBYTECODE: "1" } },
  );
  return result.stdout.trim();
}
