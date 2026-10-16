export function realExecutorEnvironment(
  environment: Readonly<Record<string, string | undefined>>,
): Readonly<{
  host: "127.0.0.1" | "::1";
  port: number;
  baseURL: string;
  resolverRule: string;
}>;
