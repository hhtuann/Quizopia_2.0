export function realExecutorEnvironment(environment) {
  const rawPort = environment.LD05_REAL_EXECUTOR_PORT ?? "3000";
  if (!/^[1-9]\d{0,4}$/.test(rawPort) || Number(rawPort) > 65535) {
    throw new Error(
      "LD05_REAL_EXECUTOR_PORT must be an integer from 1 to 65535.",
    );
  }

  const host = environment.LD05_REAL_EXECUTOR_HOST ?? "127.0.0.1";
  if (host !== "127.0.0.1" && host !== "::1") {
    throw new Error("LD05_REAL_EXECUTOR_HOST must be 127.0.0.1 or ::1.");
  }

  const port = Number(rawPort);
  const resolverHost = host === "::1" ? "[::1]" : host;
  return Object.freeze({
    host,
    port,
    baseURL: `http://localhost:${port}`,
    // Match only the harness port; Gateway/Mailpit keep normal DNS resolution.
    resolverRule: `MAP localhost:${port} ${resolverHost}`,
  });
}
