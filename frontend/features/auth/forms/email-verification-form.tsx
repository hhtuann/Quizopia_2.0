"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";
import { useAuth } from "../auth-provider";

const emailVerificationSchema = z.object({
  username: z
    .string()
    .max(255, "Username must be 255 characters or fewer.")
    .refine((value) => value.trim().length > 0, "Enter your username."),
  verificationCode: z
    .string()
    .regex(/^[0-9]{6}$/, "Enter the six-digit verification code."),
});

type EmailVerificationFields = z.infer<typeof emailVerificationSchema>;

export interface EmailVerificationFormProps {
  readonly initialUsername?: string;
}

export function EmailVerificationForm({
  initialUsername = "",
}: EmailVerificationFormProps) {
  const router = useRouter();
  const { confirmVerification, requestVerification } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
  const [requestStatus, setRequestStatus] = useState<string | null>(null);
  const [isRequesting, setIsRequesting] = useState(false);
  const {
    formState: { errors, isSubmitting },
    getValues,
    handleSubmit,
    register,
    trigger,
  } = useForm<EmailVerificationFields>({
    defaultValues: { username: initialUsername, verificationCode: "" },
    resolver: zodResolver(emailVerificationSchema),
  });

  async function onSubmit(fields: EmailVerificationFields) {
    setServerError(null);
    setRequestStatus(null);
    const result = await confirmVerification({
      otp: fields.verificationCode,
      username: fields.username,
    });
    if (result.ok) {
      router.replace("/login");
      return;
    }

    setServerError(
      result.error.kind === "api-error" &&
        result.error.error.code === "AUTH_VERIFICATION_FAILED"
        ? "Email verification failed. Check the six-digit code and try again."
        : "Email verification could not be completed. Please try again.",
    );
  }

  async function resendCode() {
    setServerError(null);
    setRequestStatus(null);
    if (!(await trigger("username"))) {
      return;
    }

    setIsRequesting(true);
    try {
      const result = await requestVerification({
        username: getValues("username"),
      });
      if (result.ok) {
        setRequestStatus(
          "If a verification code can be issued, the request has been accepted.",
        );
      } else {
        setServerError(
          "The verification request could not be completed. Please try again.",
        );
      }
    } finally {
      setIsRequesting(false);
    }
  }

  return (
    <form
      className="mt-7 space-y-5"
      noValidate
      onSubmit={handleSubmit(onSubmit, () => setServerError(null))}
    >
      <TextField
        autoComplete="username"
        error={errors.username?.message}
        label="Username"
        type="text"
        {...register("username")}
      />
      <TextField
        autoCapitalize="off"
        autoComplete="one-time-code"
        error={errors.verificationCode?.message}
        inputMode="numeric"
        label="Verification code"
        maxLength={6}
        pattern="[0-9]{6}"
        spellCheck={false}
        type="text"
        {...register("verificationCode")}
      />

      {serverError ? (
        <Alert title="Email verification failed" variant="danger">
          {serverError}
        </Alert>
      ) : null}

      {requestStatus ? (
        <Alert title="Verification request accepted">{requestStatus}</Alert>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2">
        <Button
          disabled={isSubmitting}
          isLoading={isRequesting}
          loadingLabel="Requesting code"
          onClick={() => void resendCode()}
          type="button"
          variant="secondary"
        >
          Resend code
        </Button>
        <Button
          disabled={isRequesting}
          isLoading={isSubmitting}
          loadingLabel="Verifying email"
          type="submit"
        >
          Verify email
        </Button>
      </div>
    </form>
  );
}
