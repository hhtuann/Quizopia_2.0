import type { AnchorHTMLAttributes } from "react";

export type SkipLinkProps = AnchorHTMLAttributes<HTMLAnchorElement>;

export function SkipLink({ className = "", ...props }: SkipLinkProps) {
  return (
    <a
      {...props}
      className={`fixed left-4 top-4 z-50 -translate-y-24 rounded-lg bg-primary px-4 py-3 text-sm font-semibold text-foreground-inverse shadow-primary transition-transform duration-200 focus:translate-y-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background motion-reduce:transition-none ${className}`}
    />
  );
}
