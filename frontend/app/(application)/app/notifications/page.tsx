import type { Metadata } from "next";
import { NotificationsPage } from "../../../../features/notifications/components/notification-pages";

export const metadata: Metadata = {
  title: "Assessment notifications | Quizopia 2.0",
  referrer: "same-origin",
};

export default function NotificationsRoute() {
  return <NotificationsPage />;
}
