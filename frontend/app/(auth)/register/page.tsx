import type { Metadata } from "next";
import { AuthFooterLink } from "../../../features/auth/components/auth-footer-link";
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
      <AuthFooterLink
        prompt="Already have an account?"
        label="Sign in"
        href="/login"
      />
    </>
  );
}
