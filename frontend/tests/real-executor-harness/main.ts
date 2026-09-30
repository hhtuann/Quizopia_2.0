import { createAuthSessionService } from "../../features/auth/session/auth-session-service";

const gatewayOrigin = "http://localhost:8080";
const executorTarget = `${gatewayOrigin}/api/auth/me?e2e=executor-refresh-retry`;
const service = createAuthSessionService({ apiBaseUrl: gatewayOrigin });

const loginForm = document.querySelector<HTMLFormElement>("#login-form");
const identifierInput = document.querySelector<HTMLInputElement>("#identifier");
const passwordInput = document.querySelector<HTMLInputElement>("#password");
const protectedRequestButton = document.querySelector<HTMLButtonElement>(
  "#protected-request-button",
);
const status = document.querySelector<HTMLOutputElement>("#status");

if (
  loginForm === null ||
  identifierInput === null ||
  passwordInput === null ||
  protectedRequestButton === null ||
  status === null
) {
  throw new Error("Real auth executor harness failed to initialize.");
}

function currentUserId(): string | null {
  const snapshot = service.runtime.getSnapshot();
  return snapshot.status === "authenticated" || snapshot.status === "refreshing"
    ? snapshot.user.id
    : null;
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "logging-in";
  protectedRequestButton.disabled = true;

  const result = await service.login({
    identifier: identifierInput.value,
    password: passwordInput.value,
  });

  passwordInput.value = "";
  if (!result.ok) {
    status.textContent = "login-failed";
    return;
  }

  status.dataset.authenticatedUserId = result.value.id;
  status.textContent = "login-ready";
  protectedRequestButton.disabled = false;
});

protectedRequestButton.addEventListener("click", async () => {
  protectedRequestButton.disabled = true;
  status.textContent = "protected-request-running";

  const userIdBefore = currentUserId();
  let createRequestCount = 0;
  const result = await service.authenticatedRequests.execute({
    createRequest: () => {
      createRequestCount += 1;
      return {
        headers: { accept: "application/json" },
        method: "GET",
        target: executorTarget,
      };
    },
  });
  const userIdAfter = currentUserId();

  status.dataset.createRequestCount = String(createRequestCount);
  status.dataset.resultKind = result.kind;
  status.dataset.resultStatus =
    result.kind === "response" ? String(result.response.status) : "none";
  status.dataset.sameUser = String(
    userIdBefore !== null && userIdBefore === userIdAfter,
  );

  const succeeded =
    result.kind === "response" &&
    result.response.status === 200 &&
    result.response.body.kind === "json" &&
    userIdBefore !== null &&
    userIdBefore === userIdAfter;
  status.textContent = succeeded
    ? "protected-request-complete"
    : "protected-request-failed";
});
