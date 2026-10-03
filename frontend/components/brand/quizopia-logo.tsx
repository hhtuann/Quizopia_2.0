import type { ComponentPropsWithoutRef } from "react";
import { QuizopiaBrandMark } from "./quizopia-brand-mark";
import { QuizopiaWordmark } from "./quizopia-wordmark";

export interface QuizopiaLogoProps extends ComponentPropsWithoutRef<"span"> {
  readonly markClassName?: string;
  readonly wordmarkClassName?: string;
}

export function QuizopiaLogo({
  className = "",
  markClassName = "",
  wordmarkClassName = "",
  ...props
}: QuizopiaLogoProps) {
  return (
    <span
      className={`inline-flex items-center gap-2.5 ${className}`}
      {...props}
    >
      <QuizopiaBrandMark className={markClassName} />
      <QuizopiaWordmark className={wordmarkClassName} />
    </span>
  );
}
