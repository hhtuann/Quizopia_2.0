import type { Metadata } from "next";
import Link from "next/link";
import { AuthPageHeader } from "../../../features/auth/components/auth-page-header";
import { RegistrationForm } from "../../../features/auth/forms/registration-form";

export const metadata: Metadata = {
  title: "Create account | Quizopia 2.0",
};

export default function RegisterPage() {
  return (
    <>
      <AuthPageHeader
        description="Create a local account. Your email must be verified before the account can be activated."
        title="Create your account"
      />
      <RegistrationForm />
      <p className="mt-6 text-center text-sm leading-6 text-foreground-secondary">
        Already have an account?{" "}
        <Link
          className="rounded font-semibold text-primary underline-offset-4 hover:text-primary-hover hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          href="/login"
        >
          Sign in
        </Link>
      </p>
    </>
  );
}
