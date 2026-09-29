export function publicApiTarget(
  path: string,
  apiBaseUrl = process.env.NEXT_PUBLIC_API_URL,
): string {
  if (!path.startsWith("/")) {
    throw new TypeError("Public API path must start with '/'.");
  }

  const baseUrl = apiBaseUrl?.trim().replace(/\/+$/, "") ?? "";
  return baseUrl.length === 0 ? path : `${baseUrl}${path}`;
}
