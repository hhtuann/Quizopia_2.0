import { QuizEditorPage } from "../../../../../features/quizzes/components/quiz-authoring-pages";

export default async function QuizEditorRoute({
  params,
}: {
  readonly params: Promise<{ readonly quizId: string }>;
}) {
  const { quizId } = await params;
  return <QuizEditorPage quizId={quizId} />;
}
