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
      <span className="font-heading text-lg font-normal tracking-[-0.02em]">
        <span className="text-foreground">Quiz</span>
        <span className="bg-gradient-to-r from-primary to-secondary bg-clip-text text-transparent">
          opia
        </span>
      </span>
      <span className="mt-1 font-sans text-[0.625rem] font-medium tracking-[0.08em] text-foreground-muted">
        version 2.0
      </span>
    </span>
  );
}
