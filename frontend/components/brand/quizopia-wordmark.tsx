import type { ComponentPropsWithoutRef } from "react";

export type QuizopiaWordmarkProps = ComponentPropsWithoutRef<"span">;

export function QuizopiaWordmark({
  className = "",
  ...props
}: QuizopiaWordmarkProps) {
  return (
    <span
      className={`flex flex-col items-start leading-none ${className}`}
      {...props}
    >
      <span className="font-brand text-lg font-normal tracking-[-0.02em]">
        <span className="text-foreground">Quiz</span>
        <span className="brand-gradient-text">opia</span>
      </span>
      <span className="mt-0.5 font-sans text-[0.625rem] font-medium leading-none tracking-[0.08em] text-foreground-muted">
        version 2.0
      </span>
    </span>
  );
}
