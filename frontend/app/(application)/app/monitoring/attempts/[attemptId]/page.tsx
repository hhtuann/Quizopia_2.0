import type { Metadata } from "next";
import { StudentMonitoringPage } from "../../../../../../features/proctoring/components/student-monitoring-screen";

export const metadata: Metadata = {
  title: "Attempt Monitoring | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function StudentMonitoringRoute({
  params,
}: {
  readonly params: Promise<{ readonly attemptId: string }>;
}) {
  const { attemptId } = await params;
  return <StudentMonitoringPage attemptId={attemptId} />;
}
