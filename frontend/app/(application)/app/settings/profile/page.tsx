import type { Metadata } from "next";
import { ProfileSettingsPage } from "../../../../../features/auth/components/profile-settings-page";

export const metadata: Metadata = { title: "Account settings | Quizopia 2.0" };

export default function ProfileSettingsRoute() {
  return <ProfileSettingsPage />;
}
