import { z } from "zod";

// Match Java String.strip()/Character.isWhitespace, not JS trim() (which
// removes NBSP and preserves some whitespace that Java strips).
const edgeWhitespace =
  /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g;

export function normalizeDisplayName(value: string): string {
  return value.normalize("NFC").replace(edgeWhitespace, "");
}

export const displayNameSchema = z.string().refine((value) => {
  const name = normalizeDisplayName(value);
  return (
    value === "" ||
    (name.length > 0 && name.length <= 100 && !/[\p{Cc}]/u.test(name))
  );
}, "Enter a display name of 1–100 characters without control characters, or leave it empty.");

export function optionalDisplayName(value: string): string | null {
  return value === "" ? null : normalizeDisplayName(value);
}
