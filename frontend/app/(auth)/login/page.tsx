import type { Metadata } from "next";
import { AuthFooterLink } from "../../../features/auth/components/auth-footer-link";
import { AuthPageHeader } from "../../../features/auth/components/auth-page-header";
import { LoginForm } from "../../../features/auth/forms/login-form";

export const metadata: Metadata = {
  title: "Sign in | Quizopia 2.0",
};

export default function LoginPage() {
  return (
    <>
      <AuthPageHeader
        description="Use your Quizopia username or verified email and password to access your account."
        title="Sign in"
      />
      <LoginForm />
      <AuthFooterLink
        prompt="New to Quizopia?"
        label="Create an account"
        href="/register"
      />
    </>
  );
}
