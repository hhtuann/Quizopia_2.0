import { PublicationPage } from "../../../../features/assessments/components/publication-pages";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "My Assessments | Quizopia 2.0",
  referrer: "same-origin",
};

export default function AssessmentsPage() {
  return <PublicationPage />;
}
