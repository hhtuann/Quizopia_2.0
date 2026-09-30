import type { Metadata } from "next";
import Link from "next/link";
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
