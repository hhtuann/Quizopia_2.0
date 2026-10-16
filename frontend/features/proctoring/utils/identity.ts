/**
 * UUID generation for monitoring event identity.
 *
 * eventId/streamId identify evidence, they are not secrets, but prefer the
 * platform CSPRNG. Monitoring fails closed if the required browser primitive
 * is unavailable rather than accepting collision-prone evidence identifiers.
 */
export function randomUuid(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }
  if (cryptoApi && typeof cryptoApi.getRandomValues === "function") {
    const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6]! & 0x0f) | 0x40;
    bytes[8] = (bytes[8]! & 0x3f) | 0x80;
    const hex = Array.from(bytes, (byte) =>
      byte.toString(16).padStart(2, "0"),
    ).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  throw new Error("Secure UUID generation is unavailable.");
}

/**
 * RFC3339 instant for `observedAt` (string or null only; numeric epochs are
 * rejected by the contract). Millisecond precision is accepted and normalized
 * to PostgreSQL microseconds server-side.
 */
export function nowIso(clock: () => number = Date.now): string {
  return new Date(clock()).toISOString();
}
