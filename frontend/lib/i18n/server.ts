import { cookies } from "next/headers";
import { LOCALE_COOKIE, resolveLocale } from "./core";

export async function getServerLocale() {
  return resolveLocale((await cookies()).get(LOCALE_COOKIE)?.value);
}
