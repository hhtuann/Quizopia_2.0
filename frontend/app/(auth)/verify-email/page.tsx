import type { Metadata } from "next";
import { AuthFooterLink } from "../../../features/auth/components/auth-footer-link";
import { AuthPageHeader } from "../../../features/auth/components/auth-page-header";
import { EmailVerificationForm } from "../../../features/auth/forms/email-verification-form";

export const metadata: Metadata = {
  title: "Verify email | Quizopia 2.0",
};

export default async function VerifyEmailPage({
  searchParams,
}: {
  searchParams: Promise<{ username?: string | string[] }>;
}) {
  const params = await searchParams;
  const username =
    typeof params.username === "string" ? params.username : undefined;

  return (
    <>
      <AuthPageHeader
        description="Enter the six-digit code issued for your username, or request another code."
        title="Verify your email"
      />
      <EmailVerificationForm initialUsername={username} />
      <AuthFooterLink
        prompt="Need to restart account setup?"
        label="Return to registration"
        href="/register"
      />
    </>
  );
}
