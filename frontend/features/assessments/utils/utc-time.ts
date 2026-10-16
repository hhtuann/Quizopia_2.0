const instantPattern =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?(Z|([+-])(\d{2}):(\d{2}))$/;

/** Exact microseconds: never round a scheduling comparison to milliseconds. */
export function instantMicroseconds(value: string): bigint | null {
  const match = instantPattern.exec(value);
  if (!match) return null;
  const [
    ,
    year,
    month,
    day,
    hour,
    minute,
    second,
    fraction = "",
    offset,
    sign,
    offsetHour = "0",
    offsetMinute = "0",
  ] = match;
  const numbers = [year, month, day, hour, minute, second].map(Number);
  const [y, mo, d, h, mi, s] = numbers as [
    number,
    number,
    number,
    number,
    number,
    number,
  ];
  if (y < 1 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 59)
    return null;
  const date = new Date(0);
  date.setUTCFullYear(y, mo - 1, d);
  date.setUTCHours(h, mi, s, 0);
  if (
    date.getUTCFullYear() !== y ||
    date.getUTCMonth() !== mo - 1 ||
    date.getUTCDate() !== d
  )
    return null;
  const oh = Number(offsetHour);
  const om = Number(offsetMinute);
  if (oh > 18 || om > 59 || (oh === 18 && om !== 0)) return null;
  const offsetMinutes =
    offset === "Z" ? 0 : (sign === "-" ? -1 : 1) * (oh * 60 + om);
  return (
    BigInt(date.getTime()) * BigInt(1000) +
    BigInt(fraction.padEnd(6, "0")) -
    BigInt(offsetMinutes) * BigInt(60_000_000)
  );
}

export function utcFieldToInstant(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(value))
    return null;
  const instant = `${value.length === 16 ? `${value}:00` : value}Z`;
  return instantMicroseconds(instant) === null ? null : instant;
}

export function instantToUtcField(value: string): string {
  const micros = instantMicroseconds(value);
  if (micros === null) return "";
  const fraction =
    ((micros % BigInt(1_000_000)) + BigInt(1_000_000)) % BigInt(1_000_000);
  const wholeSeconds = micros - fraction;
  const date = new Date(Number(wholeSeconds / BigInt(1000)))
    .toISOString()
    .slice(0, 19);
  return (
    date +
    (fraction === BigInt(0)
      ? ""
      : `.${String(fraction).padStart(6, "0").replace(/0+$/, "")}`)
  );
}

export function formatUtc(value: string): string {
  const field = instantToUtcField(value);
  return field ? `${field.replace("T", " ")} UTC` : "Invalid instant";
}
