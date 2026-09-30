import type { ReactNode } from "react";
import { QuizAuthoringLayout } from "../../../../features/quizzes/components/quiz-authoring-layout";

export default function QuizRoutesLayout({
  children,
}: {
  readonly children: ReactNode;
}) {
  return <QuizAuthoringLayout>{children}</QuizAuthoringLayout>;
}
