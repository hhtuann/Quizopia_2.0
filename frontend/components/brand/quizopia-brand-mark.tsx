import type { ComponentPropsWithoutRef } from "react";

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
          d="M13.25 2.5 5.75 13h5l-1 8.5L18.25 10h-5.1l.1-7.5Z"
          fill="currentColor"
          stroke="currentColor"
          strokeLinejoin="round"
          strokeWidth="1.25"
        />
      </svg>
    </span>
  );
}
