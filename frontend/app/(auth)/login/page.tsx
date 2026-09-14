import type { Metadata } from "next";
import Link from "next/link";
import { AuthPageHeader } from "../../../features/auth/components/auth-page-header";
import { LoginForm } from "../../../features/auth/forms/login-form";

export const metadata: Metadata = {
  title: "Sign in | Quizopia 2.0",
};

export default function LoginPage() {
  return (
    <>
      <AuthPageHeader
        description="Use your local Quizopia username and password to access your account."
        title="Sign in"
      />
      <LoginForm />
      <p className="mt-6 text-center text-sm leading-6 text-foreground-secondary">
        New to Quizopia?{" "}
        <Link
          className="rounded font-semibold text-primary underline-offset-4 hover:text-primary-hover hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2"
          href="/register"
        >
          Create an account
        </Link>
      </p>
    </>
  );
}
