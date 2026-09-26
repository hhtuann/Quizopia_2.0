import type { Metadata } from "next";
import { ApplicationEntry } from "../../../components/shell/application-entry";

export const metadata: Metadata = {
  title: "Application | Quizopia 2.0",
};

export default function ApplicationPage() {
  return <ApplicationEntry />;
}
