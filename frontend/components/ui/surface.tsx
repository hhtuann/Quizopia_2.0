import type { HTMLAttributes } from "react";

export type SurfaceProps = HTMLAttributes<HTMLDivElement>;

export function Surface({ className = "", ...props }: SurfaceProps) {
  return (
    <div
      {...props}
      className={`rounded-xl border border-border bg-surface shadow-card ${className}`}
    />
  );
}
