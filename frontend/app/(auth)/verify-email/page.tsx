import type { Metadata } from "next";
import Link from "next/link";
import { AuthPageHeader } from "../../../features/auth/components/auth-page-header";
import { EmailVerificationForm } from "../../../features/auth/forms/email-verification-form";

export const metadata: Metadata = {
  title: "Verify email | Quizopia 2.0",
};

export default function VerifyEmailPage() {
  return (
    <>
      <AuthPageHeader
        description="When a verification code has been issued for your account, enter it here to verify your email address."
        title="Verify your email"
      />
      <EmailVerificationForm />
      <p className="mt-6 text-center text-sm leading-6 text-foreground-secondary">
        Need to restart account setup?{" "}
        <Link
          className="rounded font-semibold text-primary underline-offset-4 hover:text-primary-hover hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          href="/register"
        >
          Return to registration
        </Link>
      </p>
    </>
  );
}
