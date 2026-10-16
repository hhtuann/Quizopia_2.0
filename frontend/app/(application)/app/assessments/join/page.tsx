import type { Metadata } from "next";
import { StudentAssessmentJoinPage } from "../../../../../features/attempts/components/student-assessment-pages";

export const metadata: Metadata = {
  title: "Enter Assessment | Quizopia 2.0",
  referrer: "no-referrer",
};

export default function AssessmentJoinRoute() {
  return <StudentAssessmentJoinPage />;
}
