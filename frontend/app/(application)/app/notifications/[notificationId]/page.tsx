import type { Metadata } from "next";
import { NotificationDetailPage } from "../../../../../features/notifications/components/notification-pages";

export const metadata: Metadata = {
  title: "Assessment notice | Quizopia 2.0",
  referrer: "same-origin",
};

export default async function NotificationDetailRoute({
  params,
}: {
  readonly params: Promise<{ readonly notificationId: string }>;
}) {
  const { notificationId } = await params;
  return <NotificationDetailPage notificationId={notificationId} />;
}
