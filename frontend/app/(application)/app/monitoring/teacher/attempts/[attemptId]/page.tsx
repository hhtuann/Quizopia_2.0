import type { Metadata } from "next";
import { TeacherMonitoringPage } from "../../../../../../../features/proctoring/components/teacher-monitoring-screen";

export const metadata: Metadata = {
  title: "Teacher Monitoring | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function TeacherMonitoringRoute({
  params,
}: {
  readonly params: Promise<{ readonly attemptId: string }>;
}) {
  const { attemptId } = await params;
  return <TeacherMonitoringPage attemptId={attemptId} />;
}
