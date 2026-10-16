import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createServer } from "vite";
import { realExecutorEnvironment } from "./runner-environment.mjs";

const runnerDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(runnerDirectory, "../..");
const harnessRoot = path.resolve(runnerDirectory, "../real-executor-harness");
const environment = realExecutorEnvironment(process.env);

const server = await createServer({
  root: harnessRoot,
  server: {
    host: environment.host,
    port: environment.port,
    strictPort: true,
  },
});

const playwrightArgs = [
  "exec",
  "playwright",
  "test",
  "--config=playwright.real-executor.config.ts",
];
const command = process.platform === "win32" ? "cmd.exe" : "pnpm";
const commandArgs =
  process.platform === "win32"
    ? ["/d", "/s", "/c", `pnpm.cmd ${playwrightArgs.join(" ")}`]
    : playwrightArgs;
let exitCode = 1;

try {
  await server.listen();
  exitCode = await new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, {
      cwd: frontendRoot,
      stdio: "inherit",
    });

    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (signal !== null) {
        reject(new Error(`Playwright terminated by signal ${signal}.`));
        return;
      }
      resolve(code ?? 1);
    });
  });
} finally {
  await server.close();
}

process.exitCode = exitCode;
