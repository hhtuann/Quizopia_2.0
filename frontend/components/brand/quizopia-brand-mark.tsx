import type { ComponentPropsWithoutRef } from "react";
import { QUIZOPIA_BOLT_PATH } from "./quizopia-brand-geometry";

export type QuizopiaBrandMarkProps = ComponentPropsWithoutRef<"span">;

export function QuizopiaBrandMark({
  className = "",
  ...props
}: QuizopiaBrandMarkProps) {
  return (
    <span
      aria-hidden="true"
      className={`flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-secondary text-foreground-inverse shadow-primary ${className}`}
      {...props}
    >
      <svg
        className="size-[62%]"
        data-testid="quizopia-brand-mark"
        fill="none"
        viewBox="0 0 24 24"
      >
        <path
          d={QUIZOPIA_BOLT_PATH}
          fill="currentColor"
          stroke="currentColor"
          strokeLinejoin="round"
          strokeWidth="1.25"
        />
      </svg>
    </span>
  );
}
