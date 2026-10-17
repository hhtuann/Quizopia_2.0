import { createServer } from "node:net";

export interface W3aRealFrontendEnvironment {
  readonly host: string;
  readonly port: number;
  readonly baseURL: string;
  readonly gatewayURL: string;
  readonly webServerCommand: string;
  readonly reuseExistingServer: false;
}

/**
 * Resolves and validates the isolated W3A real frontend environment.
 * Strict ownership: guarantees dynamic port consistency between the webServer
 * command and target URL, loopback isolation, and reuseExistingServer: false
 * to prevent silent reuse of unknown developer processes.
 */
export function resolveW3aRealFrontendEnvironment(
  env: Record<string, string | undefined> = process.env,
): W3aRealFrontendEnvironment {
  const rawFrontendUrl = env.W3A_REAL_FRONTEND_URL ?? "http://localhost:3000";
  const rawGatewayUrl = env.W3A_REAL_GATEWAY_URL ?? "http://localhost:8080";

  let parsed: URL;
  try {
    parsed = new URL(rawFrontendUrl);
  } catch {
    throw new Error(`Invalid W3A_REAL_FRONTEND_URL: ${rawFrontendUrl}`);
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(
      `W3A_REAL_FRONTEND_URL must use http or https protocol: ${rawFrontendUrl}`,
    );
  }

  const host = parsed.hostname;
  const loopbackHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (!loopbackHosts.has(host)) {
    throw new Error(
      `W3A_REAL_FRONTEND_URL host must be a loopback address (localhost, 127.0.0.1, ::1), got: ${host}`,
    );
  }

  const portStr = parsed.port || (parsed.protocol === "https:" ? "443" : "80");
  const port = Number.parseInt(portStr, 10);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error(`W3A_REAL_FRONTEND_URL has invalid port: ${portStr}`);
  }

  const webServerCommand = `pnpm exec next dev --webpack --hostname ${host} --port ${port}`;

  return {
    host,
    port,
    baseURL: rawFrontendUrl,
    gatewayURL: rawGatewayUrl,
    webServerCommand,
    reuseExistingServer: false,
  };
}

/**
 * Probes whether the target frontend port is free on loopback.
 * Fails closed if the port is occupied to ensure unknown developer listeners
 * are never silently adopted or terminated.
 */
export function assertFrontendPortFree(
  port: number,
  host: string = "127.0.0.1",
): Promise<void> {
  return new Promise((resolve, reject) => {
    const probe = createServer();
    probe.once("error", (err: NodeJS.ErrnoException) => {
      if (err.code === "EADDRINUSE" || err.code === "EACCES") {
        reject(
          new Error(
            `Port ${port} on ${host} is already in use by another process. ` +
              `W3A real acceptance requires an isolated, exclusively owned frontend server; ` +
              `failing closed to prevent silent reuse of unknown developer processes. ` +
              `Do not kill another listener.`,
          ),
        );
      } else {
        reject(err);
      }
    });
    probe.once("listening", () => {
      probe.close(() => resolve());
    });
    const bindHost = host === "localhost" ? "127.0.0.1" : host;
    probe.listen(port, bindHost);
  });
}
