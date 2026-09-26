"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Alert } from "../../../components/ui/alert";
import { Button } from "../../../components/ui/button";
import { TextField } from "../../../components/ui/text-field";

const emailVerificationSchema = z.object({
  verificationCode: z
    .string()
    .refine(
      (value) => value.trim().length > 0,
      "Enter your verification code.",
    ),
});

type EmailVerificationFields = z.infer<typeof emailVerificationSchema>;

export function EmailVerificationForm() {
  const [isIntegrationUnavailable, setIsIntegrationUnavailable] =
    useState(false);
  const {
    formState: { errors },
    handleSubmit,
    register,
  } = useForm<EmailVerificationFields>({
    defaultValues: { verificationCode: "" },
    resolver: zodResolver(emailVerificationSchema),
  });

  return (
    <form
      className="mt-7 space-y-5"
      noValidate
      onSubmit={handleSubmit(
        () => setIsIntegrationUnavailable(true),
        () => setIsIntegrationUnavailable(false),
      )}
    >
      <TextField
        autoCapitalize="off"
        autoComplete="one-time-code"
        error={errors.verificationCode?.message}
        label="Verification code"
        spellCheck={false}
        type="text"
        {...register("verificationCode")}
      />

      {isIntegrationUnavailable ? (
        <Alert title="Email verification is not available yet">
          Your verification code was not sent or saved. Please try again when
          email verification becomes available.
        </Alert>
      ) : null}

      <Button className="w-full" type="submit">
        Verify email
      </Button>
    </form>
  );
}
