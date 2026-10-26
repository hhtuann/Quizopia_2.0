import { randomBytes, randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
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
  if (url.hostname === "127.0.0.1") {
    url.hostname = "localhost";
  }
  return url.origin;
}

function resolveStateEndpoints(): { gateway?: string; mailpit?: string } {
  const dir = process.env.W3A_REAL_STATE_DIRECTORY;
  if (!dir) return {};
  try {
    const raw = readFileSync(join(dir, "state.json"), "utf8");
    const parsed = JSON.parse(raw);
    return parsed?.endpoints ?? {};
  } catch {
    return {};
  }
}
const stateEndpoints = resolveStateEndpoints();

export const gateway = loopbackOrigin(
  process.env.W3A_REAL_GATEWAY_URL ??
    stateEndpoints.gateway ??
    "http://localhost:8080",
);
const mailpit = loopbackOrigin(
  process.env.W3A_REAL_MAILPIT_URL ??
    stateEndpoints.mailpit ??
    "http://127.0.0.1:8025",
);
const publicationBase = "/api/assessments/publications";

export function matchGatewayUrl(
  url: string,
  pathOrPrefix: string,
  exact = false,
): boolean {
  try {
    const u = new URL(url);
    const g = new URL(gateway);
    const loopbacks = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);
    const isLoopback = loopbacks.has(u.hostname) && loopbacks.has(g.hostname);
    const samePort =
      (u.port || (u.protocol === "https:" ? "443" : "80")) ===
      (g.port || (g.protocol === "https:" ? "443" : "80"));
    if (!isLoopback || !samePort) return false;
    return exact
      ? u.pathname === pathOrPrefix
      : u.pathname.startsWith(pathOrPrefix);
  } catch {
    return false;
  }
}

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
      if (matchGatewayUrl(request.url(), "/")) {
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
        matchGatewayUrl(response.url(), "/api/auth/me", true) &&
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
    await this.page.bringToFront();
    await this.page.waitForLoadState("domcontentloaded");
    const evaluateSnapshot = async () => {
      return this.page.evaluate(async () => ({
        cookie: document.cookie,
        local: JSON.stringify(localStorage),
        session: JSON.stringify(sessionStorage),
        links: [...document.querySelectorAll("a")].map((link) => link.href),
        indexedDbNames:
          "databases" in indexedDB
            ? (await indexedDB.databases()).map(
                (database) => database.name ?? "",
              )
            : [],
      }));
    };
    let snapshot: {
      cookie: string;
      local: string;
      session: string;
      links: string[];
      indexedDbNames: string[];
    };
    try {
      snapshot = await evaluateSnapshot();
    } catch (err: unknown) {
      if (
        err instanceof Error &&
        err.message.includes("Execution context was destroyed")
      ) {
        await this.page.waitForLoadState("domcontentloaded");
        snapshot = await evaluateSnapshot();
      } else {
        throw err;
      }
    }
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
      matchGatewayUrl(
        response.url(),
        "/api/auth/email-verification/request",
        true,
      ) && response.request().method() === "POST",
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
      matchGatewayUrl(response.url(), "/api/auth/me", true) &&
      response.status() === 200,
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
  const enabled = page.waitForResponse((response) =>
    matchGatewayUrl(response.url(), "/api/auth/teacher-enablement", true),
  );
  await page.getByRole("menuitem", { name: "Register as teacher" }).click();
  const res = await enabled;
  assertStatus(res.status(), 204, "real Teacher enablement");
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

// Quiz authoring helpers assert English UI strings while the app defaults to
// Vietnamese. Pin the locale cookie for future loads and switch the live SPA
// locale through the user menu language options without reloading the page.
async function pinEnglishUi(page: Page) {
  const origin = new URL(page.url()).origin;
  if (origin.startsWith("http")) {
    await page
      .context()
      .addCookies([{ name: "quizopia-locale", value: "en", url: origin }]);
  }
  if ((await page.evaluate(() => document.documentElement.lang)) !== "en") {
    // These helpers always run on authenticated pages, where the language
    // options live in the user menu (menuitemradio), not a header combobox.
    // The trigger label is localized, so match it in either UI language.
    await page
      .getByRole("button", {
        name: /Open user menu for|Mở menu người dùng cho/,
      })
      .click();
    await page.getByRole("menuitemradio", { name: "English" }).click();
    // Close the still-open menu so it cannot cover later interactions.
    await page.keyboard.press("Escape");
  }
}

export async function ensureTeachingWorkspace(page: Page) {
  await pinEnglishUi(page);
  const authoringLink = page.getByRole("link", {
    name: "Quiz authoring",
    exact: true,
  });
  if (await authoringLink.isVisible().catch(() => false)) {
    return;
  }
  await page.getByRole("button", { name: /Open user menu for/ }).click();
  const switchItem = page.getByRole("menuitem", { name: "Switch to Teaching" });
  await switchItem.waitFor({ state: "visible", timeout: 5_000 });
  await switchItem.click();
  await authoringLink.waitFor({ state: "visible", timeout: 10_000 });
}

export async function publishExactSource(page: Page) {
  await pinEnglishUi(page);
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

export async function publishMultiQuestionSource(page: Page) {
  await pinEnglishUi(page);
  await page.getByRole("link", { name: "Quiz authoring", exact: true }).click();
  await page.getByRole("link", { name: "Create quiz" }).click();
  await expect(page).toHaveURL(/\/app\/quizzes\/[0-9a-f-]+$/);
  const quizId = new URL(page.url()).pathname.split("/").at(-1)!;
  await page
    .getByRole("textbox", { name: "Quiz title" })
    .fill("Isolated W3 Multi-Question Source");
  await page
    .getByRole("textbox", { name: "Quiz Markdown source" })
    .fill(
      "Câu 1 [SINGLE_CHOICE]: Which protocol serves web pages?\n*A. HTTP\nB. FTP\nC. SSH\nD. SMTP\n\n" +
        "Câu 2 [MULTIPLE_CHOICE]: Which of these are transport protocols?\n*A. TCP\n*B. UDP\nC. IP\nD. DNS\n\n" +
        "Câu 3 [TRUE_FALSE_MATRIX]: Network layer facts.\n*A. IPv6 has 128-bit addresses\nB. IPv4 has 64-bit addresses\n*C. Routers operate at Layer 3\nD. Switches operate at Layer 4\n\n" +
        "Câu 4 [NUMERIC_FILL]: Compute 10 divided by 4.\nĐáp án: 2.50\n",
    );
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page
    .getByLabel("Description")
    .fill("Disposable multi-question real integration source");
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
      matchGatewayUrl(response.url(), publicationBase) &&
      new URL(response.url()).pathname.endsWith(suffix) &&
      response.request().method() === method,
  );
}

export function attemptResponse(
  page: Page,
  suffix: string,
  method: string,
): Promise<Response> {
  return page.waitForResponse(
    (response) =>
      matchGatewayUrl(response.url(), "/api/assessments/") &&
      new URL(response.url()).pathname.endsWith(suffix) &&
      response.request().method() === method,
  );
}

export function utcField(offsetMinutes: number, fraction: string) {
  return `${new Date(Date.now() + offsetMinutes * 60_000).toISOString().slice(0, 19)}.${fraction}`;
}

/**
 * Local date/time parts (plus the exact emitted UTC instant) for filling the
 * shared DateTimeField inputs with a now-offset instant in any runner timezone.
 */
export function utcLocalParts(offsetMinutes: number): {
  date: string;
  time: string;
  instant: string;
} {
  const d = new Date(Date.now() + offsetMinutes * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
    instant: d.toISOString(),
  };
}

export { publicationBase };

export async function fixtureControl(
  action:
    | "pause-quiz"
    | "resume-quiz"
    | "pause-gateway"
    | "resume-gateway"
    | "seed-membership"
    | "remove-membership"
    | "probe-recipient-eligibility"
    | "activate-edge-attestation"
    | "activate-closure-worker"
    | "reset-closure-flags"
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
